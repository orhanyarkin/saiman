package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.VerifyResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import io.github.orhanyarkin.x402.facilitator.FacilitatorException;
import io.github.orhanyarkin.x402.facilitator.HttpFacilitatorClient;
import io.github.orhanyarkin.x402.facilitator.SupportedResponse;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Facilitator verify/settle observations and the single settle-failure WARN, for both payment
 * flows, against a real HTTP fake facilitator wrapped so individual calls can be made to throw or
 * to carry a hostile {@code errorMessage}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FacilitatorTelemetryIntegrationTests.TestApplication.class)
@AutoConfigureRestTestClient
class FacilitatorTelemetryIntegrationTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    private static final String PAY_TO = TestWallets.OTHER_PAYER.address();
    private static final String PRICE = "10000";
    private static final String HOSTILE_TEXT = "SECRET-FACILITATOR-MESSAGE-do-not-log";
    private static final AtomicReference<RuntimeException> SETTLE_THROWS = new AtomicReference<>();
    private static final AtomicReference<RuntimeException> VERIFY_THROWS = new AtomicReference<>();

    /** When non-null, verify and settle are answered by a raw server with this 200 body. */
    private static final AtomicReference<String> GARBAGE_BODY = new AtomicReference<>();

    private static final AtomicReference<String> SETTLE_GARBAGE = new AtomicReference<>();
    private static final AtomicInteger GARBAGE_HITS = new AtomicInteger();
    private static final AtomicBoolean THROW_ON_START = new AtomicBoolean();
    private static final List<X402PaymentFailedEvent> FAILED_EVENTS = new CopyOnWriteArrayList<>();
    private static final HttpServer GARBAGE_SERVER = startGarbageServer();

    private static HttpServer startGarbageServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                GARBAGE_HITS.incrementAndGet();
                byte[] body = String.valueOf(GARBAGE_BODY.get()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void x402Properties(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> PAY_TO);
        registry.add("x402.server.facilitator.url", FACILITATOR::url);
    }

    @AfterAll
    static void stopFacilitator() {
        GARBAGE_SERVER.stop(0);
        FACILITATOR.close();
    }

    /** Every key-value of every stopped observation, as text. */
    private static final List<String> STOPPED_CONTEXTS = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean HANDLER_REGISTERED = new AtomicBoolean();

    @Autowired
    private RestTestClient client;

    @Autowired
    private X402Codec codec;

    @Autowired
    private TestObservationRegistry observations;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger starterLogger;

    @BeforeEach
    void reset() {
        FACILITATOR.resetInjectedFailures();
        FACILITATOR.resetCallCounts();
        SETTLE_THROWS.set(null);
        VERIFY_THROWS.set(null);
        GARBAGE_BODY.set(null);
        SETTLE_GARBAGE.set(null);
        GARBAGE_HITS.set(0);
        THROW_ON_START.set(false);
        FAILED_EVENTS.clear();
        observations.clear();
        STOPPED_CONTEXTS.clear();
        if (HANDLER_REGISTERED.compareAndSet(false, true)) {
            observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    String name = context.getName();
                    return THROW_ON_START.get() && name != null && name.startsWith("x402.facilitator.");
                }

                @Override
                public void onStart(Observation.Context context) {
                    throw new IllegalStateException("handler onStart blew up");
                }
            });
            observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                    var parent = context.getParentObservation();
                    STOPPED_CONTEXTS.add(context.getName()
                            + " parent="
                            + (parent == null ? "-" : parent.getContextView().getName())
                            + " "
                            + context.getAllKeyValues());
                }
            });
        }
        starterLogger = (Logger) LoggerFactory.getLogger("io.github.orhanyarkin.x402");
        logs.start();
        starterLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        starterLogger.detachAppender(logs);
        logs.stop();
        logs.list.clear();
    }

    private PaymentRequirements offer(PaymentFlow flow) {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                PRICE,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                flow == PaymentFlow.UPFRONT
                        ? Map.of(
                                "name",
                                TestnetAssets.USDC_NAME,
                                "version",
                                TestnetAssets.USDC_VERSION,
                                "paymentFlow",
                                "upfront")
                        : Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
    }

    private PaymentPayload newPayload(PaymentFlow flow) {
        return PaymentPayloads.build(TestWallets.PAYER, offer(flow));
    }

    private int pay(String uri, PaymentPayload payload) {
        return client.get()
                .uri(uri)
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .returnResult()
                .getStatus()
                .value();
    }

    private void assertObservation(String name, String outcome, String reason) {
        observations
                .assertThat()
                .hasObservationWithNameEqualTo(name)
                .that()
                .hasLowCardinalityKeyValue("outcome", outcome)
                .hasLowCardinalityKeyValue("reason", reason);
    }

    private List<String> warnLines() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> settleFailureWarnLines() {
        return warnLines().stream()
                .filter(m -> m.startsWith("x402 settlement failed"))
                .toList();
    }

    @Test
    void successRecordsVerifyAndSettleObservationsWithoutWarn() {
        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(200);

        assertObservation("x402.facilitator.verify", "success", "none");
        assertObservation("x402.facilitator.settle", "success", "none");
        assertThat(settleFailureWarnLines()).isEmpty();
        assertThat(STOPPED_CONTEXTS)
                .anyMatch(c -> c.startsWith("x402.facilitator.settle parent=x402.server.payment "))
                .anyMatch(c -> c.startsWith("x402.facilitator.verify parent=x402.server.payment "));
    }

    @Test
    void defaultFlowSuccessIsObservedToo() {
        assertThat(pay("/default/ok", newPayload(PaymentFlow.AUTHORIZATION))).isEqualTo(200);

        assertObservation("x402.facilitator.verify", "success", "none");
        assertObservation("x402.facilitator.settle", "success", "none");
    }

    @Test
    void rejectedSettleWithKnownReasonIsTaggedAndLoggedOnceWithOnlySafeFields() {
        FACILITATOR.injectSettleFailure("invalid_exact_evm_transaction_failed");
        PaymentPayload payload = newPayload(PaymentFlow.UPFRONT);
        Eip3009Authorization authorization = payload.payload().authorization();

        assertThat(pay("/upfront/ok", payload)).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "rejected", "invalid_exact_evm_transaction_failed");
        List<String> lines = settleFailureWarnLines();
        assertThat(lines).hasSize(1);
        String line = lines.getFirst();
        assertThat(line)
                .contains("reason=invalid_exact_evm_transaction_failed")
                .contains("payer=" + authorization.from())
                .contains("nonceRef=" + FacilitatorTelemetry.nonceRef(authorization.from(), authorization.nonce()))
                .contains("validAfter=" + authorization.validAfter())
                .contains("validBefore=" + authorization.validBefore())
                .containsPattern("secondsLeft=\\d+")
                .containsPattern("verifyToSettleGapMs=\\d+")
                .containsPattern("settleDurationMs=\\d+")
                .contains("facilitatorStatus=200")
                .contains("txHashPresent=false");
    }

    @Test
    void defaultFlowSettleFailureIsObservedAndLoggedToo() {
        FACILITATOR.injectSettleFailure("invalid_exact_evm_failed_to_get_receipt");

        assertThat(pay("/default/ok", newPayload(PaymentFlow.AUTHORIZATION))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "rejected", "invalid_exact_evm_failed_to_get_receipt");
        assertThat(settleFailureWarnLines()).hasSize(1);
    }

    @Test
    void unknownShapeValidReasonIsTaggedOtherButLoggedVerbatim() {
        FACILITATOR.injectSettleFailure("some_new_facilitator_code_42");

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "rejected", "other");
        assertThat(settleFailureWarnLines().getFirst()).contains("reason=some_new_facilitator_code_42");
    }

    @Test
    void settlementPendingIsAmbiguous() {
        FACILITATOR.injectSettleFailure("settlement_pending");

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "ambiguous", "settlement_pending");
    }

    @Test
    void successWithoutTransactionIsMalformed() {
        FACILITATOR.injectSettleSuccessWithoutTransaction();

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "malformed", "none");
        assertThat(settleFailureWarnLines()).hasSize(1);
    }

    @Test
    void transportErrorOnSettle() {
        SETTLE_THROWS.set(new FacilitatorException("the x402 facilitator call failed"));

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "transport_error", "none");
        assertThat(settleFailureWarnLines().getFirst())
                .contains("facilitatorStatus=0")
                .contains("outcome=transport_error");
    }

    @Test
    void openCircuitOnSettle() {
        SETTLE_THROWS.set(new FacilitatorException(
                "the x402 facilitator circuit breaker is open", FacilitatorException.Failure.CIRCUIT_OPEN, 0));

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "circuit_open", "none");
    }

    @Test
    void undecodableSettleAnswerIsMalformed() {
        SETTLE_THROWS.set(new FacilitatorException(
                "the x402 facilitator response could not be decoded", FacilitatorException.Failure.MALFORMED, 200));

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "malformed", "none");
    }

    @Test
    void invalidVerifyIsRejectedWithItsReason() {
        FACILITATOR.injectVerifyInvalid("insufficient_funds");

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.verify", "rejected", "insufficient_funds");
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void verifyTransportErrorAndHttp4xx() {
        VERIFY_THROWS.set(new FacilitatorException("the x402 facilitator call failed"));
        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);
        assertObservation("x402.facilitator.verify", "transport_error", "none");

        observations.clear();
        VERIFY_THROWS.set(null);
        FACILITATOR.injectVerifyClientError(1);
        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);
        assertObservation("x402.facilitator.verify", "rejected", "none");
    }

    private long stoppedCount(String name) {
        return STOPPED_CONTEXTS.stream().filter(c -> c.startsWith(name + " ")).count();
    }

    @Test
    void nullSettleBodyIsMalformedAndFailsSafeInBothFlows() {
        for (String[] flow : new String[][] {{"/upfront/ok", "upfront"}, {"/default/ok", "authorization"}}) {
            observations.clear();
            STOPPED_CONTEXTS.clear();
            logs.list.clear();
            FAILED_EVENTS.clear();
            GARBAGE_HITS.set(0);
            PaymentFlow paymentFlow = flow[1].equals("upfront") ? PaymentFlow.UPFRONT : PaymentFlow.AUTHORIZATION;
            PaymentPayload payload = newPayload(paymentFlow);
            // verify must pass for real; only settle gets the garbage answer
            GARBAGE_BODY.set(null);
            SETTLE_GARBAGE.set("null");

            assertThat(pay(flow[0], payload)).isEqualTo(402);

            assertObservation("x402.facilitator.settle", "malformed", "none");
            assertThat(stoppedCount("x402.facilitator.settle")).isEqualTo(1);
            assertThat(settleFailureWarnLines()).hasSize(1);
            assertThat(settleFailureWarnLines().getFirst()).contains("facilitatorStatus=200");
            assertThat(FAILED_EVENTS).hasSize(1);
            int hits = GARBAGE_HITS.get();
            // claim held: the same payload is a replay and never reaches the facilitator again
            assertThat(pay(flow[0], payload)).isEqualTo(402);
            assertThat(GARBAGE_HITS.get()).isEqualTo(hits);
            SETTLE_GARBAGE.set(null);
        }
    }

    @Test
    void oversizedSettleBodyIsMalformedWithRealStatus() {
        SETTLE_GARBAGE.set("x".repeat(HttpFacilitatorClient.MAX_RESPONSE_BYTES + 10));

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);

        assertObservation("x402.facilitator.settle", "malformed", "none");
        assertThat(settleFailureWarnLines()).hasSize(1);
        assertThat(settleFailureWarnLines().getFirst()).contains("facilitatorStatus=200");
        assertThat(FAILED_EVENTS).hasSize(1);
    }

    @Test
    void nullVerifyBodyIsMalformedAndReleasesTheClaim() {
        PaymentPayload payload = newPayload(PaymentFlow.UPFRONT);
        GARBAGE_BODY.set("null");

        assertThat(pay("/upfront/ok", payload)).isEqualTo(402);

        assertObservation("x402.facilitator.verify", "malformed", "none");
        assertThat(FACILITATOR.settleCallCount()).isZero();
        // Documented rule: a verify that never produced an answer released the claim (nothing was
        // or will be settled under it), so the same authorization can be retried.
        GARBAGE_BODY.set(null);
        assertThat(pay("/upfront/ok", payload)).isEqualTo(200);
    }

    @Test
    void throwingObservationStartNeverChangesAnOutcome() {
        THROW_ON_START.set(true);

        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(200);
        assertThat(pay("/default/ok", newPayload(PaymentFlow.AUTHORIZATION))).isEqualTo(200);

        FACILITATOR.injectSettleFailure("invalid_exact_evm_transaction_failed");
        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);
        assertThat(settleFailureWarnLines()).hasSize(1);
        FACILITATOR.resetInjectedFailures();
        FACILITATOR.injectVerifyInvalid("insufficient_funds");
        assertThat(pay("/upfront/ok", newPayload(PaymentFlow.UPFRONT))).isEqualTo(402);
    }

    @Test
    void signatureNonceAndFacilitatorMessageNeverReachLogsOrObservations() {
        FACILITATOR.injectSettleFailure("invalid_exact_evm_transaction_failed");
        PaymentPayload payload = newPayload(PaymentFlow.UPFRONT);
        String signature = payload.payload().signature();
        String nonce = payload.payload().authorization().nonce();

        assertThat(pay("/upfront/ok", payload)).isEqualTo(402);
        // A second, authorization-flow failure with a different payload, for the other code path.
        FACILITATOR.injectSettleFailure("invalid_exact_evm_transaction_failed");
        PaymentPayload second = newPayload(PaymentFlow.AUTHORIZATION);
        assertThat(pay("/default/ok", second)).isEqualTo(402);

        List<String> everything = logs.list.stream()
                .map(e -> e.getFormattedMessage() + " " + e.getThrowableProxy())
                .toList();
        List<String> observed = List.copyOf(STOPPED_CONTEXTS);
        for (String secret : List.of(
                signature,
                nonce,
                nonce.substring(2),
                second.payload().signature(),
                second.payload().authorization().nonce(),
                HOSTILE_TEXT)) {
            assertThat(everything).noneMatch(line -> line.contains(secret));
            assertThat(observed).noneMatch(kv -> kv.contains(secret));
        }
        assertThat(observed).anyMatch(kv -> kv.contains("x402.facilitator.settle") || kv.contains("outcome"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        Controller controller() {
            return new Controller();
        }

        @Bean
        Object failedEventRecorder() {
            return new Object() {
                @EventListener
                @SuppressWarnings("unused")
                void on(X402PaymentFailedEvent event) {
                    FAILED_EVENTS.add(event);
                }
            };
        }

        @Bean
        TestObservationRegistry testObservationRegistry() {
            return TestObservationRegistry.create();
        }

        @Bean
        PaymentNonceStore x402InMemoryPaymentNonceStoreForTests() {
            return new InMemoryPaymentNonceStore();
        }

        /** The real HTTP client against the fake, with injectable exceptions and a hostile errorMessage. */
        @Bean
        FacilitatorClient facilitatorClient(X402Codec codec) {
            HttpFacilitatorClient delegate = new HttpFacilitatorClient(
                    RestClient.builder(), FACILITATOR.url(), Duration.ofSeconds(3), Duration.ofSeconds(5), codec);
            HttpFacilitatorClient garbage = new HttpFacilitatorClient(
                    RestClient.builder(),
                    "http://127.0.0.1:" + GARBAGE_SERVER.getAddress().getPort(),
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(5),
                    codec);
            return new FacilitatorClient() {
                @Override
                public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements) {
                    RuntimeException failure = VERIFY_THROWS.get();
                    if (failure != null) {
                        throw failure;
                    }
                    VerifyResponse real =
                            (GARBAGE_BODY.get() != null ? garbage : delegate).verify(payload, requirements);
                    return new VerifyResponse(
                            real.isValid(), real.invalidReason(), HOSTILE_TEXT, real.payer(), null, null, null);
                }

                @Override
                public SettlementResponse settle(PaymentPayload payload, PaymentRequirements requirements) {
                    RuntimeException failure = SETTLE_THROWS.get();
                    if (failure != null) {
                        throw failure;
                    }
                    if (SETTLE_GARBAGE.get() != null) {
                        GARBAGE_BODY.set(SETTLE_GARBAGE.get());
                    }
                    SettlementResponse real =
                            (SETTLE_GARBAGE.get() != null ? garbage : delegate).settle(payload, requirements);
                    return new SettlementResponse(
                            real.success(),
                            real.errorReason(),
                            HOSTILE_TEXT,
                            real.payer(),
                            real.transaction(),
                            real.network(),
                            real.amount(),
                            null,
                            null,
                            null);
                }

                @Override
                public SupportedResponse supported() {
                    return delegate.supported();
                }
            };
        }
    }

    @RestController
    static class Controller {

        @GetMapping("/default/ok")
        @RequiresPayment(price = PRICE)
        String defaultOk() {
            return "paid content";
        }

        @GetMapping("/upfront/ok")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        String upfrontOk() {
            return "paid content";
        }
    }
}
