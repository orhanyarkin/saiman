package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money-invariant tests against {@link FakeFacilitator} failure injection (blocking review item
 * 9): a facilitator timeout never leaks the handler body and never double-charges a retry; a
 * facilitator rejection keeps the nonce claim; a transient {@code 5xx} from {@code /verify} is
 * retried; a {@code 4xx}/{@code 429} is not.
 *
 * <p>A short {@code x402.server.facilitator.read-timeout} (separate from {@link
 * RequiresPaymentIntegrationTests}' context, which uses the default) is what makes the timeout
 * test fast and deterministic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = X402FacilitatorResilienceTests.TestApplication.class)
@AutoConfigureRestTestClient
class X402FacilitatorResilienceTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    private static final String PAY_TO = TestWallets.OTHER_PAYER.address();
    private static final String PRICE = "10000";

    @DynamicPropertySource
    static void x402Properties(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> PAY_TO);
        registry.add("x402.server.facilitator.url", FACILITATOR::url);
        registry.add("x402.server.facilitator.read-timeout", () -> "500ms");
        registry.add("x402.server.max-timeout-seconds", () -> "300");
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
    private X402ServerProperties properties;

    private PaymentRequirements offer() {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                PRICE,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                properties.maxTimeoutSeconds(),
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
    }

    @Test
    void settleTimeoutReturns402WithNoBodyLeakAndKeepsTheClaimForReplay() {
        FACILITATOR.injectSettleDelay(Duration.ofSeconds(5));
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        String body = client.get()
                .uri("/paid")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull().doesNotContain("paid content");
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);

        // The claim is still held: the same authorization is rejected as a replay, not re-verified.
        FACILITATOR.resetInjectedFailures();
        long verifyCallsBeforeReplay = FACILITATOR.verifyCallCount();
        client.get()
                .uri("/paid")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);
        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(verifyCallsBeforeReplay);
    }

    @Test
    void verifyInvalidReturns402AndKeepsTheClaimForReplay() {
        FACILITATOR.injectVerifyInvalid("insufficient_funds");
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        client.get()
                .uri("/paid")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        // The claim is still held: even after the injected invalid response is cleared, the same
        // authorization is rejected as a replay before /verify is even called again.
        FACILITATOR.resetInjectedFailures();
        long verifyCallsBefore = FACILITATOR.verifyCallCount();
        client.get()
                .uri("/paid")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);
        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(verifyCallsBefore);
    }

    @Test
    void transientVerifyServerErrorIsRetried() {
        FACILITATOR.injectVerifyServerError(1);
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(FACILITATOR.verifyCallCount()).isGreaterThan(1);
    }

    @Test
    void verifyClientErrorIsNotRetried() {
        // A raw HTTP 429 (or any 4xx) from /verify -- distinct from a well-formed isValid=false
        // JSON body -- must never be retried: HttpFacilitatorClient.decode() throws
        // FacilitatorClientErrorException for 4xx, which the retry policy ignores.
        FACILITATOR.injectVerifyClientError(3);
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        PaidController paidController() {
            return new PaidController();
        }

        @Bean
        PaymentNonceStore x402InMemoryPaymentNonceStoreForTests() {
            return new InMemoryPaymentNonceStore();
        }
    }

    @RestController
    static class PaidController {

        @GetMapping("/paid")
        @RequiresPayment(price = PRICE)
        String paid() {
            return "paid content";
        }
    }
}
