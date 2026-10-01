package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.saiman.sellerapi.SellerApiApplication;
import io.github.orhanyarkin.saiman.testsupport.RedisContainerConfiguration;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared wiring for the settlement-record tests: a real Postgres (the imported {@link
 * TestcontainersConfiguration}), the shared Redis for the nonce store and a {@link FakeFacilitator}, all JVM singletons
 * so Spring's context cache stays valid across test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = SellerApiApplication.class)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, RedisContainerConfiguration.class})
public abstract class SettlementTestBase {

    public static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    public static final String PAY_TO = TestWallets.OTHER_PAYER.address();
    public static final String PRICE = "10000";

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> PAY_TO);
        registry.add("x402.server.facilitator.url", FACILITATOR::url);
    }

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected X402Codec codec;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper json;

    @BeforeEach
    void resetState() {
        FACILITATOR.resetInjectedFailures();
        FACILITATOR.resetCallCounts();
        jdbc.sql("DELETE FROM event_publication").update();
        jdbc.sql("DELETE FROM settlement").update();
        jdbc.sql("DELETE FROM credit_note").update();
    }

    protected final PaymentRequirements offer() {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                PRICE,
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

    protected final PaymentPayload newPayload() {
        return PaymentPayloads.build(TestWallets.PAYER, offer());
    }

    protected final RestTestClient.ResponseSpec getSummary(PaymentPayload payload) {
        return client.get()
                .uri("/v1/disclosures/THYAO/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange();
    }

    protected final long creditNoteRows() {
        return jdbc.sql("SELECT count(*) FROM credit_note").query(Long.class).single();
    }

    protected final long settlementRows() {
        return jdbc.sql("SELECT count(*) FROM settlement").query(Long.class).single();
    }

    /** The persisted publications' JSON, oldest first, with their event type's simple class name. */
    protected final List<Map.Entry<String, JsonNode>> publications() {
        return jdbc.sql("SELECT event_type, serialized_event FROM event_publication ORDER BY publication_date")
                .query((rs, row) -> {
                    String type = rs.getString("event_type");
                    return Map.entry(
                            type.substring(type.lastIndexOf('.') + 1), json.readTree(rs.getString("serialized_event")));
                })
                .list();
    }
}
