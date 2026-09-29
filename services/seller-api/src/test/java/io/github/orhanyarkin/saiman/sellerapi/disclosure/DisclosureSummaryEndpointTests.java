package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.SellerApiApplication;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.server.PaymentNonceStore;
import io.github.orhanyarkin.x402.server.RedisPaymentNonceStore;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end acceptance for the first paid seller-api resource, {@code GET
 * /v1/disclosures/{ticker}/summary}: no payment, a valid payment, a replayed payment, an unknown
 * ticker and a malformed ticker, against a real Valkey-backed {@link PaymentNonceStore} and a
 * {@link FakeFacilitator} (docs/design/m1-x402.md, "seller-api" and "Server flow").
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = SellerApiApplication.class)
@AutoConfigureRestTestClient
class DisclosureSummaryEndpointTests {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.2-alpine")).withExposedPorts(6379);

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    private static final String PAY_TO = TestWallets.OTHER_PAYER.address();
    private static final String PRICE = "10000";

    @DynamicPropertySource
    static void x402Properties(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> PAY_TO);
        registry.add("x402.server.facilitator.url", FACILITATOR::url);
    }

    @AfterAll
    static void stopFacilitator() {
        FACILITATOR.close();
    }

    @BeforeEach
    void resetFacilitator() {
        FACILITATOR.resetInjectedFailures();
        FACILITATOR.resetCallCounts();
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private X402Codec codec;

    @Autowired
    private PaymentNonceStore nonceStore;

    @Test
    void nonceStoreIsRedisBacked() {
        assertThat(nonceStore).isInstanceOf(RedisPaymentNonceStore.class);
    }

    private PaymentRequirements offer() {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                PRICE,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
    }

    @Test
    void noPaymentReturns402WithDecodablePaymentRequired() {
        ExchangeResult result = client.get()
                .uri("/v1/disclosures/THYAO/summary")
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .expectHeader()
                .exists(X402Headers.PAYMENT_REQUIRED)
                .returnResult();

        String header = result.getResponseHeaders().getFirst(X402Headers.PAYMENT_REQUIRED);
        assertThat(header).isNotNull();
        PaymentRequired paymentRequired = codec.decodePaymentRequired(header);
        assertThat(paymentRequired.x402Version()).isEqualTo(2);
        assertThat(paymentRequired.resource().url()).isEqualTo("/v1/disclosures/THYAO/summary");
        assertThat(paymentRequired.accepts()).hasSize(1);
        PaymentRequirements accepted = paymentRequired.accepts().get(0);
        assertThat(accepted.amount()).isEqualTo("10000");
        assertThat(accepted.payTo()).isEqualTo(PAY_TO);
        assertThat(accepted.network()).isEqualTo("eip155:84532");
        assertThat(accepted.scheme()).isEqualTo(TestnetAssets.SCHEME_EXACT);
    }

    @Test
    void validPaymentReturnsTheSummaryAndSettles() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/v1/disclosures/THYAO/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectBody()
                .jsonPath("$.ticker")
                .isEqualTo("THYAO")
                .jsonPath("$.dataSource")
                .isEqualTo("fixture")
                .jsonPath("$.citations[0].chunkId")
                .exists();

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void replayedPaymentIsRejected() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        client.get()
                .uri("/v1/disclosures/ASELS/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isOk();

        client.get()
                .uri("/v1/disclosures/ASELS/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void unknownTickerWithAValidPaymentReturns404AndIsNotCharged() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/v1/disclosures/ZZZZZZ/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isNotFound();

        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void malformedTickerWithAValidPaymentReturns400AndIsNotCharged() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/v1/disclosures/thyao/summary")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isBadRequest();

        assertThat(FACILITATOR.settleCallCount()).isZero();
    }
}
