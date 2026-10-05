package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Saturated dashboard reads answer a fixed 503 and never keep the spend guard from reserving. */
@TestPropertySource(properties = "saiman.orchestrator.dashboard.max-concurrent-reads=1")
class DashboardSaturationTests extends RunTestSupport {

    @Autowired
    private BoundedReads reads;

    @Test
    void aSaturatedReadIs503AndABudgetReservationStillSucceeds() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Object> slow = pool.submit(() -> reads.read(() -> {
                inFlight.countDown();
                return jdbc.sql("SELECT pg_sleep(2)").query().listOfRows();
            }));
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            String body = http.get()
                    .uri("/api/v1/spend")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .valueEquals("Retry-After", "5")
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(body).contains("\"detail\":\"too many reads in flight; retry\"");

            client.send(newIntent(createRun(50_000)), null); // reserve, sign, settle while reads are saturated
            assertThat(signer.calls()).isEqualTo(1);

            slow.get(10, TimeUnit.SECONDS);
        }
        http.get().uri("/api/v1/spend").exchange().expectStatus().isOk();
    }
}
