package io.github.orhanyarkin.saiman.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * {@link BoundedReads} and the error bodies of {@link ApiExceptionHandler} against the real Postgres: a read over its
 * statement timeout is a fixed 503 and leaves no timeout behind on the pooled connection; a full bulkhead is a fixed
 * 503 while postings still go through; an unexpected {@code DataAccessException} is a fixed 500 without SQL or path.
 * The forced failures go through a test-only controller in a standalone MockMvc with the real advice, so the shared
 * application context (and its OpenAPI contract) stays unchanged.
 */
@LedgerIntegrationTest
class BoundedReadsTests {

    @Autowired
    private BoundedReads reads;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private org.springframework.test.web.servlet.client.RestTestClient client;

    private final SplittableRandom random = new SplittableRandom();

    /**
     * Test-only routes; {@code /api/...} so the advice reports the route template as {@code instance}. An inner
     * (non-static) class: component scanning only considers independent classes, so it can never enter the
     * application context or the OpenAPI contract.
     */
    @Controller
    class ProbeController {
        private final BoundedReads reads;
        private final JdbcClient jdbc;

        ProbeController(BoundedReads reads, JdbcClient jdbc) {
            this.reads = reads;
            this.jdbc = jdbc;
        }

        @GetMapping("/api/v1/probe/sleep/{marker}")
        @ResponseBody
        Integer sleep(@PathVariable String marker) {
            return reads.read(() ->
                    jdbc.sql("SELECT 1 FROM pg_sleep(1)").query(Integer.class).single());
        }

        @GetMapping("/api/v1/probe/broken/{marker}")
        @ResponseBody
        Integer broken(@PathVariable String marker) {
            return reads.read(() -> jdbc.sql("SELECT zzsecret_column FROM payment LIMIT 1")
                    .query(Integer.class)
                    .single());
        }
    }

    @Test
    void readOverTheTimeoutIsAFixed503AndTheTimeoutDoesNotLeak() throws Exception {
        BoundedReads shortReads = new BoundedReads(transactionManager, jdbc, Duration.ofMillis(100), 4);

        assertThatThrownBy(() -> shortReads.read(() -> jdbc.sql("SELECT 1 FROM pg_sleep(1)")
                        .query(Integer.class)
                        .single()))
                .isInstanceOf(BoundedReads.ReadTimeoutException.class);

        String body = mvc(shortReads)
                .perform(get("/api/v1/probe/sleep/zzpathmark"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value("The read took too long; retry later"))
                .andExpect(jsonPath("$.instance").value("/api/v1/probe/sleep/%7Bmarker%7D"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("zzpathmark").doesNotContain("pg_sleep").doesNotContain("cancel");

        // set_config(..., true) is transaction-local: every pooled connection is back to no limit, and a statement
        // longer than the 100 ms limit outside BoundedReads succeeds (more calls than the pool has connections).
        for (int i = 0; i < 12; i++) {
            assertThat(jdbc.sql("SELECT current_setting('statement_timeout')")
                            .query(String.class)
                            .single())
                    .isEqualTo("0");
            assertThat(jdbc.sql("SELECT 1 FROM pg_sleep(0.15)")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }
    }

    @Test
    void unexpectedDataAccessExceptionIsAFixed500WithoutSqlOrPath() throws Exception {
        String body = mvc(reads)
                .perform(get("/api/v1/probe/broken/zzpathmark"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("internal error"))
                .andExpect(jsonPath("$.instance").value("/api/v1/probe/broken/%7Bmarker%7D"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("zzpathmark")
                .doesNotContain("zzsecret")
                .doesNotContain("SELECT")
                .doesNotContain("SQL");
    }

    /**
     * With every permit held by slow reads, further dashboard requests are refused at once (503, Retry-After) instead
     * of queueing for connections, and a posting from the Kafka path still gets a connection and commits.
     */
    @Test
    void fullBulkheadRefusesReadsButPostingsStillSucceed() throws Exception {
        int limit = 4;
        CountDownLatch entered = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> holders = new ArrayList<>();
        try {
            for (int i = 0; i < limit; i++) {
                holders.add(Thread.ofVirtual()
                        .start(() -> reads.read(() -> {
                            // Inside the transaction: the connection is taken, like a slow query's.
                            jdbc.sql("SELECT 1").query(Integer.class).single();
                            entered.countDown();
                            try {
                                return release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        })));
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            for (String uri : List.of(
                    "/api/v1/ledger/revenue", "/api/v1/ledger/payments", "/api/v1/reconciliation/runs?limit=1")) {
                client.get()
                        .uri(uri)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(503)
                        .expectHeader()
                        .valueEquals("Retry-After", "5")
                        .expectHeader()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        .expectBody()
                        .jsonPath("$.detail")
                        .isEqualTo("Too many concurrent reads; retry later");
            }

            TestPayment p = TestPayment.random(random, 13_000);
            ledger.record(PaymentFact.of(p.authorized()), PaymentTopics.AUTHORIZED);
            ledger.record(PaymentFact.of(p.buyerSettled()), PaymentTopics.SETTLED);
            assertThat(jdbc.sql("SELECT buyer_state FROM payment WHERE payment_key = :key")
                            .param("key", p.key())
                            .query(String.class)
                            .single())
                    .isEqualTo("SETTLED");
        } finally {
            release.countDown();
            for (Thread t : holders) {
                t.join(10_000);
            }
        }
        client.get().uri("/api/v1/ledger/revenue").exchange().expectStatus().isOk();
    }

    private MockMvc mvc(BoundedReads with) {
        return MockMvcBuilders.standaloneSetup(new ProbeController(with, jdbc))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
