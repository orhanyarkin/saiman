package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.VerifyResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import io.github.orhanyarkin.x402.facilitator.HttpFacilitatorClient;
import io.github.orhanyarkin.x402.facilitator.SupportedResponse;
import io.github.orhanyarkin.x402.observation.X402ObservationKeys;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * The window check between {@code /verify} and an upfront {@code /settle}: a {@code /verify} that
 * (with its retries) uses up the authorization's window down to less than the settle margin
 * (connect 3 s + read 15 s + 5 s = 23 s by default) is refused with {@code 402} before anything
 * is settled, and the nonce claim is released, so the very same authorization can be retried.
 *
 * <p>The slow {@code /verify} is simulated by a {@link FacilitatorClient} decorator that moves a
 * test clock forward while verifying: deterministic, and it lets the test move the clock back to
 * prove the release with the same signed authorization.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = UpfrontPreSettleWindowIntegrationTests.TestApplication.class)
@AutoConfigureRestTestClient
class UpfrontPreSettleWindowIntegrationTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    private static final String PAY_TO = TestWallets.OTHER_PAYER.address();
    private static final String PRICE = "10000";
    private static final Instant START = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final SteppingClock CLOCK = new SteppingClock(START);

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
    private TestObservationRegistry observations;

    @BeforeEach
    void reset() {
        FACILITATOR.resetInjectedFailures();
        FACILITATOR.resetCallCounts();
        CLOCK.set(START);
        CLOCK.verifyTakes(Duration.ZERO);
        Recorder.clear();
        Controller.runs.set(0);
        observations.clear();
    }

    private static PaymentRequirements upfrontOffer() {
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
                        "paymentFlow",
                        "upfront"));
    }

    /** Valid for 60 s from the test clock's {@link #START}. */
    private String payment() {
        return PaymentPayloads.header(codec, PaymentPayloads.build(TestWallets.PAYER, upfrontOffer(), START));
    }

    private RestTestClient.ResponseSpec get(String header) {
        return client.get()
                .uri("/upfront/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange();
    }

    @Test
    void aVerifyThatLeavesLessThanTheSettleMarginIsRefusedBeforeSettlingAndReleasesTheClaim() {
        String header = payment();
        // 60 s window, 23 s margin: 38 s inside /verify leave 22 s, one second short.
        CLOCK.verifyTakes(Duration.ofSeconds(38));

        get(header).expectStatus().isEqualTo(402).expectHeader().exists(X402Headers.PAYMENT_REQUIRED);

        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(Controller.runs.get()).isZero();
        assertThat(Recorder.settled).isEmpty();
        assertThat(Recorder.failed).isEmpty();
        assertThat(Recorder.paidFailed).isEmpty();
        assertOutcome("window_too_short");

        // The claim was released: the same signed authorization, retried in time, is served.
        CLOCK.set(START);
        CLOCK.verifyTakes(Duration.ZERO);
        get(header).expectStatus().isOk();
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(Controller.runs.get()).isEqualTo(1);
        assertThat(Recorder.settled).hasSize(1);
    }

    @Test
    void aVerifyThatLeavesExactlyTheSettleMarginStillSettles() {
        CLOCK.verifyTakes(Duration.ofSeconds(37)); // 23 s left: exactly the margin

        get(payment()).expectStatus().isOk();

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(Controller.runs.get()).isEqualTo(1);
    }

    @Test
    void aFreshAuthorizationWorksAfterARefusal() {
        CLOCK.verifyTakes(Duration.ofSeconds(50));
        get(payment()).expectStatus().isEqualTo(402);

        CLOCK.set(START);
        CLOCK.verifyTakes(Duration.ZERO);
        get(payment()).expectStatus().isOk();

        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(2);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    private void assertOutcome(String outcome) {
        observations
                .assertThat()
                .hasObservationWithNameEqualTo(X402ObservationKeys.OBSERVATION_NAME)
                .that()
                .hasLowCardinalityKeyValue(X402ObservationKeys.OUTCOME, outcome)
                .hasLowCardinalityKeyValue(X402ObservationKeys.PAYMENT_FLOW, "upfront");
    }

    /** A clock the test sets, which {@link #verifyTakes} moves forward on every {@code /verify}. */
    static final class SteppingClock extends Clock {

        private final AtomicReference<Instant> now;
        private final AtomicReference<Duration> verifyTakes = new AtomicReference<>(Duration.ZERO);

        SteppingClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        void verifyTakes(Duration duration) {
            verifyTakes.set(duration);
        }

        void verified() {
            now.updateAndGet(current -> current.plus(verifyTakes.get()));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        Clock x402TestClock() {
            return CLOCK;
        }

        /** The real HTTP client against the fake facilitator; every /verify advances the clock. */
        @Bean
        FacilitatorClient slowVerifyFacilitatorClient(
                RestClient.Builder restClientBuilder, X402ServerProperties properties, X402Codec codec) {
            X402ServerProperties.Facilitator facilitator = properties.facilitator();
            FacilitatorClient http = new HttpFacilitatorClient(
                    restClientBuilder,
                    facilitator.url(),
                    facilitator.connectTimeout(),
                    facilitator.readTimeout(),
                    codec);
            return new FacilitatorClient() {
                @Override
                public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements) {
                    VerifyResponse response = http.verify(payload, requirements);
                    CLOCK.verified();
                    return response;
                }

                @Override
                public SettlementResponse settle(PaymentPayload payload, PaymentRequirements requirements) {
                    return http.settle(payload, requirements);
                }

                @Override
                public SupportedResponse supported() {
                    return http.supported();
                }
            };
        }

        @Bean
        Controller controller() {
            return new Controller();
        }

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        TestObservationRegistry testObservationRegistry() {
            return TestObservationRegistry.create();
        }

        /** Deterministic in-memory nonce store: see RequiresPaymentIntegrationTests.TestApplication. */
        @Bean
        PaymentNonceStore x402InMemoryPaymentNonceStoreForTests() {
            return new InMemoryPaymentNonceStore();
        }
    }

    @RestController
    static class Controller {

        static final AtomicInteger runs = new AtomicInteger();

        @GetMapping("/upfront/ok")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        String ok() {
            runs.incrementAndGet();
            return "paid content";
        }
    }

    static final class Recorder {
        static final List<X402PaymentSettledEvent> settled = Collections.synchronizedList(new ArrayList<>());
        static final List<X402PaymentFailedEvent> failed = Collections.synchronizedList(new ArrayList<>());
        static final List<X402PaidRequestFailedEvent> paidFailed = Collections.synchronizedList(new ArrayList<>());

        static void clear() {
            settled.clear();
            failed.clear();
            paidFailed.clear();
        }

        @EventListener
        void onSettled(X402PaymentSettledEvent event) {
            settled.add(event);
        }

        @EventListener
        void onFailed(X402PaymentFailedEvent event) {
            failed.add(event);
        }

        @EventListener
        void onPaidFailed(X402PaidRequestFailedEvent event) {
            paidFailed.add(event);
        }
    }
}
