package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.SellerApiApplication;
import io.github.orhanyarkin.saiman.testsupport.RedisContainerConfiguration;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Shared wiring for the RAG-mode endpoint tests: the shared Redis (nonce store, router cost guard is
 * replaced by the fake router, summary cache), a {@link FakeFacilitator}, a {@link
 * FakeIngestServer} and a {@link SwitchableRouter}. Everything is a JVM singleton started once, so
 * Spring's context cache stays valid across test classes (a per-class {@code @Container} would be
 * restarted on a new port under a cached context).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SellerApiApplication.class,
        properties = {"seller.disclosures.source=rag", "seller.ingest.retry-wait=5ms"})
@AutoConfigureRestTestClient
@Import({RagTestBase.RouterConfig.class, TestcontainersConfiguration.class, RedisContainerConfiguration.class})
public abstract class RagTestBase {

    public static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    public static final FakeIngestServer INGEST = new FakeIngestServer();
    public static final String PAY_TO = TestWallets.OTHER_PAYER.address();

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> PAY_TO);
        registry.add("x402.server.facilitator.url", FACILITATOR::url);
        registry.add("seller.ingest.base-url", INGEST::url);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RouterConfig {
        @Bean
        SwitchableRouter switchableRouter() {
            return new SwitchableRouter();
        }
    }

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected X402Codec codec;

    @Autowired
    protected SwitchableRouter router;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetFakes() {
        // Summary cache, single-flight locks, negative cache and run-guard counters: per-test state.
        Set<String> keys = redis.keys("seller:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        FACILITATOR.resetInjectedFailures();
        FACILITATOR.resetCallCounts();
        INGEST.reset();
        router.replyWith("{}");
        // Settlement records, credit notes and outbox publications: per-test state as well.
        jdbc.sql("DELETE FROM event_publication").update();
        jdbc.sql("DELETE FROM settlement").update();
        jdbc.sql("DELETE FROM credit_note").update();
    }

    protected final PaymentRequirements offer(String price) {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                price,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of(
                        "name",
                        TestnetAssets.USDC_NAME,
                        "version",
                        TestnetAssets.USDC_VERSION,
                        PaymentFlow.EXTRA_KEY,
                        PaymentFlow.UPFRONT.wireValue()));
    }

    /** A fresh, validly signed {@code PAYMENT-SIGNATURE} header value for {@code price} atomic units. */
    protected final String payment(String price) {
        return PaymentPayloads.header(codec, PaymentPayloads.build(TestWallets.PAYER, offer(price)));
    }

    /**
     * A fresh, validly signed header whose authorization is valid for exactly {@code windowSeconds}
     * more seconds (the default {@link #payment} uses the offer's 60).
     */
    protected final String paymentWithWindow(String price, long windowSeconds) {
        long now = Instant.now().getEpochSecond();
        Eip3009Authorization authorization = new Eip3009Authorization(
                TestWallets.PAYER.address(),
                PAY_TO,
                price,
                Long.toString(now - 5),
                Long.toString(now + windowSeconds),
                Eip3009TypedData.randomNonce());
        return PaymentPayloads.header(codec, PaymentPayloads.sign(TestWallets.PAYER, offer(price), authorization));
    }

    /** The {@code credit_note} rows, oldest first. */
    protected final List<Map<String, Object>> creditNotes() {
        return jdbc.sql("SELECT * FROM credit_note ORDER BY created_at").query().listOfRows();
    }

    /**
     * Exactly one credit note exists (upfront flow, ADR-0021: paid and not served) for {@code status},
     * with the starter's reason code for that status class.
     */
    protected final void assertOneCreditNote(int status) {
        List<Map<String, Object>> notes = creditNotes();
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0))
                .containsEntry("http_status", status)
                .containsEntry("reason_code", status >= 500 ? "handler_server_error" : "handler_client_error");
    }

    protected final RestTestClient.ResponseSpec getPaid(String uri, String price) {
        return client.get()
                .uri(uri)
                .header(X402Headers.PAYMENT_SIGNATURE, payment(price))
                .exchange();
    }

    protected final RestTestClient.ResponseSpec postPaid(String uri, String price, String json) {
        return postWith(uri, payment(price), json);
    }

    protected final RestTestClient.ResponseSpec postWith(String uri, String paymentHeader, String json) {
        return client.post()
                .uri(uri)
                .header(X402Headers.PAYMENT_SIGNATURE, paymentHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange();
    }
}
