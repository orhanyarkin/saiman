package io.github.orhanyarkin.x402.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.server.RequiresPayment;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0006 amendment, end to end: a real settled payment produces an {@code x402.server.payment}
 * {@link Observation} whose key-values never carry the {@code PAYMENT-SIGNATURE} header value, the
 * EIP-3009 signature, or the authorization nonce -- only the allowlisted {@link
 * X402ObservationKeys}, and no key name matches a payment header or contains {@code signature},
 * {@code payload} or {@code authorization}.
 *
 * <p>Asserted with a {@link TestObservationRegistry} (Micrometer's own test support), not exported
 * OpenTelemetry spans: this module depends on {@code io.micrometer:micrometer-observation} and its
 * test companion only, not the full tracing/OTel export pipeline (see {@code
 * X402ObservationAutoConfiguration}'s Javadoc on why {@code micrometer-core} is not on this
 * starter's classpath) -- the same {@link X402RedactingObservationFilter} applies to any {@link
 * io.micrometer.observation.ObservationRegistry} implementation, this one included.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = X402ObservationRedactionTests.TestApplication.class)
@AutoConfigureRestTestClient
@Import(X402ObservationRedactionTests.TestObservationRegistryConfiguration.class)
class X402ObservationRedactionTests {

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

    @Autowired
    private RestTestClient client;

    @Autowired
    private X402Codec codec;

    @Autowired
    private TestObservationRegistry observationRegistry;

    @BeforeEach
    void resetRegistry() {
        observationRegistry.clear();
    }

    @Test
    void settledPaymentObservationNeverCarriesTheHeaderSignatureOrNonce() {
        PaymentRequirements offer = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                PRICE,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer);
        String header = PaymentPayloads.header(codec, payload);
        String signature = payload.payload().signature();
        String nonce = payload.payload().authorization().nonce();

        client.get()
                .uri("/paid")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isOk();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> observationRegistry
                        .assertThat()
                        .hasNumberOfObservationsWithNameEqualTo(X402ObservationKeys.OBSERVATION_NAME, 1));

        observationRegistry.assertThat().hasHandledContextsThatSatisfy(contexts -> {
            assertThat(contexts).isNotEmpty();
            for (Observation.Context context : contexts) {
                List<io.micrometer.common.KeyValue> keyValues = new java.util.ArrayList<>();
                context.getLowCardinalityKeyValues().forEach(keyValues::add);
                context.getHighCardinalityKeyValues().forEach(keyValues::add);
                for (io.micrometer.common.KeyValue keyValue : keyValues) {
                    String key = keyValue.getKey();
                    String value = keyValue.getValue();
                    assertThat(value)
                            .as("value of %s", key)
                            .doesNotContain(header)
                            .doesNotContain(signature)
                            .doesNotContain(nonce);
                    assertThat(key.toLowerCase(Locale.ROOT))
                            .as("key name %s", key)
                            .doesNotContain("signature")
                            .doesNotContain("payload")
                            .doesNotContain("authorization");
                }
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        PaidController paidController() {
            return new PaidController();
        }

        // Deterministic in-memory nonce store: see RequiresPaymentIntegrationTests.TestApplication
        // for why this must be explicit (a lazily-connecting StringRedisTemplate bean can win the
        // @ConditionalOnMissingBean race with no real Redis running, failing claim() at request time).
        @Bean
        io.github.orhanyarkin.x402.server.PaymentNonceStore x402InMemoryPaymentNonceStoreForTests() {
            return new io.github.orhanyarkin.x402.server.InMemoryPaymentNonceStore();
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestObservationRegistryConfiguration {

        @Bean
        TestObservationRegistry testObservationRegistry() {
            return TestObservationRegistry.create();
        }
    }
}
