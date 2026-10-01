package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.observation.X402ObservationKeys;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import io.micrometer.observation.tck.TestObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The x402 {@code upfront} flow end to end (ADR-0021, docs/design/m4b-settle-first.md): settle
 * before the handler, a paid failure answered with its own status plus {@code PAYMENT-RESPONSE}
 * and reported as {@link X402PaidRequestFailedEvent}, the nonce claim never released. Also the
 * proof that an {@code authorization} handler's offer is unchanged.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = UpfrontPaymentFlowIntegrationTests.TestApplication.class)
@AutoConfigureRestTestClient
class UpfrontPaymentFlowIntegrationTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    private static final String PAY_TO = TestWallets.OTHER_PAYER.address();
    private static final String PRICE = "10000";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        Recorder.clear();
        UpfrontController.runs.set(0);
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

    private String payment() {
        return PaymentPayloads.header(codec, PaymentPayloads.build(TestWallets.PAYER, upfrontOffer()));
    }

    private RestTestClient.ResponseSpec get(String uri, String header) {
        return client.get()
                .uri(uri)
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange();
    }

    private SettlementResponse paymentResponse(ExchangeResult result) {
        String header = result.getResponseHeaders().getFirst(X402Headers.PAYMENT_RESPONSE);
        assertThat(header).as("PAYMENT-RESPONSE").isNotNull();
        return codec.decodeSettlementResponse(header);
    }

    @Test
    void defaultHandlersOfferIsUnchangedSnapshot() {
        ExchangeResult result = client.get()
                .uri("/default/ok")
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .returnResult();
        String header = result.getResponseHeaders().getFirst(X402Headers.PAYMENT_REQUIRED);
        assertThat(header).isNotNull();

        // Compared as a JSON tree: the extra map is a Map.of, whose iteration order is salted per
        // JVM, so the base64 text itself is not stable across runs (and never was).
        JsonNode actual = JSON.readTree(new String(Base64.getDecoder().decode(header), StandardCharsets.UTF_8));
        JsonNode expected = JSON.readTree("""
                {"x402Version":2,
                 "error":"payment is required to access this resource",
                 "resource":{"url":"/default/ok","description":"default resource","mimeType":"application/json"},
                 "accepts":[{"scheme":"exact","network":"eip155:84532","amount":"10000",
                             "asset":"0x036CbD53842c5426634e7929541eC2318f3dCF7e",
                             "payTo":"%s","maxTimeoutSeconds":60,
                             "extra":{"name":"USDC","version":"2"}}]}
                """.formatted(PAY_TO));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void upfrontOfferAnnouncesTheFlow() {
        ExchangeResult result = client.get()
                .uri("/upfront/ok")
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .returnResult();

        PaymentRequired required =
                codec.decodePaymentRequired(result.getResponseHeaders().getFirst(X402Headers.PAYMENT_REQUIRED));
        assertThat(required.accepts()).containsExactly(upfrontOffer());
        assertThat(PaymentFlow.of(required.accepts().getFirst())).isEqualTo(PaymentFlow.UPFRONT);
        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void settlesBeforeTheHandlerAndServesWithThePaymentResponse() {
        ExchangeResult result = get("/upfront/ok", payment())
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("paid content")
                .returnResult();

        assertThat(UpfrontController.settlesSeenByHandler.get()).isEqualTo(1);
        assertThat(UpfrontController.settledSeenByHandler.get()).isTrue();
        SettlementResponse settlement = paymentResponse(result);
        assertThat(settlement.success()).isTrue();
        assertThat(UpfrontController.txHashSeenByHandler.get()).isEqualTo(settlement.transaction());
        assertThat(Recorder.settled).hasSize(1);
        assertThat(Recorder.settled.getFirst().transactionHash()).isEqualTo(settlement.transaction());
        assertThat(Recorder.paidFailed).isEmpty();
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOutcome("settled");
    }

    @Test
    void aSettledUpfrontPaymentCannotBeReplayedAndIsNotSettledTwice() {
        String header = payment();
        get("/upfront/ok", header).expectStatus().isOk();

        get("/upfront/ok", header).expectStatus().isEqualTo(402);

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(UpfrontController.runs.get()).isEqualTo(1);
    }

    @Test
    void aFailedSettleAnswers402NeverRunsTheHandlerAndKeepsTheClaim() {
        FACILITATOR.injectSettleFailure("insufficient_funds");
        String header = payment();

        get("/upfront/ok", header)
                .expectStatus()
                .isEqualTo(402)
                .expectHeader()
                .exists(X402Headers.PAYMENT_REQUIRED)
                .expectHeader()
                .doesNotExist(X402Headers.PAYMENT_RESPONSE);

        assertThat(UpfrontController.runs.get()).isZero();
        assertThat(Recorder.failed).hasSize(1);
        assertThat(Recorder.failed.getFirst().errorReason()).isEqualTo("insufficient_funds");
        assertThat(Recorder.settled).isEmpty();
        assertThat(Recorder.paidFailed).isEmpty();
        assertOutcome("settlement_failed");

        // The claim is kept (ambiguous outcome): the same authorization is refused as a replay,
        // without a second verify or settle, even once the facilitator works again.
        FACILITATOR.resetInjectedFailures();
        long verifies = FACILITATOR.verifyCallCount();
        get("/upfront/ok", header).expectStatus().isEqualTo(402);
        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(verifies);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(UpfrontController.runs.get()).isZero();
    }

    @Test
    void aSettleWithoutATransactionHashIsAFailedSettle() {
        FACILITATOR.injectSettleSuccessWithoutTransaction();

        get("/upfront/ok", payment()).expectStatus().isEqualTo(402);

        assertThat(UpfrontController.runs.get()).isZero();
        assertThat(Recorder.failed).hasSize(1);
        assertThat(Recorder.settled).isEmpty();
    }

    @Test
    void aShortWindowIsRefusedBeforeAnyFacilitatorCall() {
        long now = Instant.now().getEpochSecond();
        Eip3009Authorization shortWindow = new Eip3009Authorization(
                TestWallets.PAYER.address(),
                PAY_TO,
                PRICE,
                Long.toString(now - 5),
                Long.toString(now + 30),
                Eip3009TypedData.randomNonce());
        String header =
                PaymentPayloads.header(codec, PaymentPayloads.sign(TestWallets.PAYER, upfrontOffer(), shortWindow));

        get("/upfront/strict-window", header).expectStatus().isEqualTo(402);

        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(UpfrontController.runs.get()).isZero();
        assertOutcome("window_too_short");
    }

    @Test
    void anExceptionHandler503IsPaidNotServedWithTheProblemBodyAndPaymentResponse() {
        String header = payment();
        ExchangeResult result = get("/upfront/model-unavailable", header)
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentTypeCompatibleWith("application/problem+json")
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("model unavailable"))
                .returnResult();

        SettlementResponse settlement = paymentResponse(result);
        assertThat(settlement.success()).isTrue();
        assertThat(Recorder.settled).hasSize(1);
        assertThat(Recorder.paidFailed).hasSize(1);
        X402PaidRequestFailedEvent event = Recorder.paidFailed.getFirst();
        assertThat(event.httpStatus()).isEqualTo(503);
        assertThat(event.reasonCode()).isEqualTo("handler_server_error");
        assertThat(event.transactionHash()).isEqualTo(settlement.transaction());
        assertThat(event.from()).isEqualTo(TestWallets.PAYER.address());
        assertThat(event.value()).isEqualTo(PRICE);
        assertThat(event.requirements()).isEqualTo(upfrontOffer());
        assertOutcome("paid_not_served");

        // Paid once, never served, and never runnable again on the same authorization.
        get("/upfront/model-unavailable", header).expectStatus().isEqualTo(402);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(Recorder.paidFailed).hasSize(1);
    }

    @Test
    void aThrownExceptionIsA500ProblemWithThePaymentResponseAndNoHandlerHeaders() {
        ExchangeResult result = get("/upfront/boom", payment())
                .expectStatus()
                .isEqualTo(500)
                .expectHeader()
                .contentTypeCompatibleWith("application/problem+json")
                .expectHeader()
                .doesNotExist("X-Download-Url")
                .expectHeader()
                .doesNotExist("Set-Cookie")
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("boom").doesNotContain("secret"))
                .returnResult();

        assertThat(paymentResponse(result).success()).isTrue();
        assertThat(Recorder.paidFailed).singleElement().satisfies(event -> {
            assertThat(event.httpStatus()).isEqualTo(500);
            assertThat(event.reasonCode()).isEqualTo("handler_exception");
        });
        assertOutcome("paid_not_served");
    }

    @Test
    void aHandler404IsPaidNotServed() {
        ExchangeResult result = get("/upfront/not-found", payment())
                .expectStatus()
                .isNotFound()
                .expectBody(String.class)
                .isEqualTo("unknown ticker")
                .returnResult();

        assertThat(paymentResponse(result).success()).isTrue();
        assertThat(Recorder.paidFailed).singleElement().satisfies(event -> {
            assertThat(event.httpStatus()).isEqualTo(404);
            assertThat(event.reasonCode()).isEqualTo("handler_client_error");
        });
    }

    @Test
    void sendErrorIsCapturedSoThePaymentResponseStillFits() {
        ExchangeResult result = get("/upfront/send-error", payment())
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .contentTypeCompatibleWith("application/problem+json")
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("secret reason"))
                .returnResult();

        assertThat(paymentResponse(result).success()).isTrue();
        assertThat(Recorder.paidFailed).singleElement().satisfies(event -> {
            assertThat(event.httpStatus()).isEqualTo(429);
            assertThat(event.reasonCode()).isEqualTo("handler_client_error");
        });
    }

    @Test
    void aResponseStatusExceptionWithoutProblemDetailsIsCapturedToo() {
        ExchangeResult result = get("/upfront/status-exception", payment())
                .expectStatus()
                .isEqualTo(400)
                .returnResult();

        assertThat(paymentResponse(result).success()).isTrue();
        assertThat(Recorder.paidFailed)
                .singleElement()
                .satisfies(event -> assertThat(event.httpStatus()).isEqualTo(400));
    }

    @Test
    void aRedirectIsPaidNotServed() {
        ExchangeResult result = get("/upfront/redirect", payment())
                .expectStatus()
                .isEqualTo(302)
                .expectHeader()
                .valueEquals("Location", "/upfront/ok")
                .returnResult();

        assertThat(paymentResponse(result).success()).isTrue();
        assertThat(Recorder.paidFailed).singleElement().satisfies(event -> {
            assertThat(event.httpStatus()).isEqualTo(302);
            assertThat(event.reasonCode()).isEqualTo("handler_redirect");
        });
    }

    @Test
    void aSendRedirectIsCapturedAsWell() {
        ExchangeResult result = get("/upfront/send-redirect", payment())
                .expectStatus()
                .isEqualTo(302)
                .returnResult();

        assertThat(paymentResponse(result).success()).isTrue();
        assertThat(Recorder.paidFailed)
                .singleElement()
                .satisfies(event -> assertThat(event.reasonCode()).isEqualTo("handler_redirect"));
    }

    @Test
    void aFailingPaidFailureListenerDoesNotChangeTheResponse() {
        Recorder.throwOnPaidFailed = true;
        try {
            ExchangeResult result = get("/upfront/model-unavailable", payment())
                    .expectStatus()
                    .isEqualTo(503)
                    .returnResult();
            assertThat(paymentResponse(result).success()).isTrue();
        } finally {
            Recorder.throwOnPaidFailed = false;
        }
    }

    @Test
    void aProblemBodyThatCannotBeWrittenStillPublishesThePaidFailureOnce() {
        ExchangeResult result = client.get()
                .uri("/upfront/boom")
                .header(X402Headers.PAYMENT_SIGNATURE, payment())
                .header(UnwritableProblemFilter.TRIGGER_HEADER, "true")
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .returnResult();

        assertThat(paymentResponse(result).success()).isTrue();
        assertThat(Recorder.paidFailed)
                .singleElement()
                .satisfies(event -> assertThat(event.reasonCode()).isEqualTo("handler_exception"));
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOutcome("paid_not_served");
    }

    @Test
    void anUpfrontHandlerThatGoesAsyncIsReportedAndKeepsTheClaim() {
        String header = payment();
        get("/upfront/async", header);

        assertThat(Recorder.paidFailed)
                .singleElement()
                .satisfies(event -> assertThat(event.reasonCode()).isEqualTo("async_not_supported"));
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOutcome("paid_not_served");

        // The claim is kept: the same authorization cannot buy a second run or a second settle.
        get("/upfront/async", header).expectStatus().isEqualTo(402);
        assertThat(UpfrontController.runs.get()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(Recorder.paidFailed).hasSize(1);
    }

    private void assertOutcome(String outcome) {
        observations
                .assertThat()
                .hasObservationWithNameEqualTo(X402ObservationKeys.OBSERVATION_NAME)
                .that()
                .hasLowCardinalityKeyValue(X402ObservationKeys.OUTCOME, outcome)
                .hasLowCardinalityKeyValue(X402ObservationKeys.PAYMENT_FLOW, "upfront");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        UpfrontController upfrontController() {
            return new UpfrontController();
        }

        @Bean
        ModelAdvice modelAdvice() {
            return new ModelAdvice();
        }

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        /** Runs outside X402SettlementFilter so the response it wraps is the one the filter writes to. */
        @Bean
        FilterRegistrationBean<UnwritableProblemFilter> unwritableProblemFilter() {
            FilterRegistrationBean<UnwritableProblemFilter> registration =
                    new FilterRegistrationBean<>(new UnwritableProblemFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
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

    /**
     * When the trigger header is present, makes setting a Problem Details content type fail the way
     * a broken or already-committed response would, so the filter's problem write throws.
     */
    static final class UnwritableProblemFilter extends OncePerRequestFilter {

        static final String TRIGGER_HEADER = "X-Test-Unwritable-Problem";

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            if (request.getHeader(TRIGGER_HEADER) == null) {
                filterChain.doFilter(request, response);
                return;
            }
            filterChain.doFilter(request, new HttpServletResponseWrapper(response) {
                @Override
                public void setContentType(String type) {
                    if (type.startsWith("application/problem+json")) {
                        throw new IllegalStateException("response not writable");
                    }
                    super.setContentType(type);
                }
            });
        }
    }

    static final class ModelUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @RestControllerAdvice
    static class ModelAdvice {
        @ExceptionHandler(ModelUnavailableException.class)
        ProblemDetail modelUnavailable() {
            return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "model unavailable");
        }
    }

    @RestController
    static class UpfrontController {

        static final AtomicInteger runs = new AtomicInteger();
        static final AtomicLong settlesSeenByHandler = new AtomicLong();
        static final AtomicReference<Boolean> settledSeenByHandler = new AtomicReference<>(false);
        static final AtomicReference<String> txHashSeenByHandler = new AtomicReference<>("");

        @GetMapping("/default/ok")
        @RequiresPayment(price = PRICE, description = "default resource")
        String defaultOk() {
            return "paid content";
        }

        @GetMapping("/upfront/ok")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        String ok(HttpServletRequest request) {
            runs.incrementAndGet();
            settlesSeenByHandler.set(FACILITATOR.settleCallCount());
            settledSeenByHandler.set(X402PaymentContext.settled(request));
            String txHash = X402PaymentContext.transactionHash(request);
            txHashSeenByHandler.set(txHash == null ? "" : txHash);
            return "paid content";
        }

        @GetMapping("/upfront/strict-window")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT, minWindowSeconds = 45)
        String strictWindow() {
            runs.incrementAndGet();
            return "paid content";
        }

        @GetMapping("/upfront/model-unavailable")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        String modelUnavailable() {
            runs.incrementAndGet();
            throw new ModelUnavailableException();
        }

        @GetMapping("/upfront/boom")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        String boom(HttpServletResponse response) {
            response.setHeader("X-Download-Url", "https://internal.example/secret-file");
            response.addCookie(new jakarta.servlet.http.Cookie("session", "leaked-session-id"));
            throw new IllegalStateException("boom secret");
        }

        /** Starts raw servlet async processing, which X402SettlementFilter can only detect after the fact. */
        @GetMapping("/upfront/async")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        void startsAsync(HttpServletRequest request, HttpServletResponse response) {
            runs.incrementAndGet();
            request.startAsync(request, response).complete();
        }

        @GetMapping("/upfront/not-found")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        ResponseEntity<String> notFound() {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("unknown ticker");
        }

        @GetMapping("/upfront/send-error")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        void sendError(HttpServletResponse response) throws IOException {
            response.sendError(429, "secret reason");
        }

        @GetMapping("/upfront/status-exception")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        String statusException() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret reason");
        }

        @GetMapping("/upfront/redirect")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        ResponseEntity<Void> redirect() {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(java.net.URI.create("/upfront/ok"))
                    .build();
        }

        @GetMapping("/upfront/send-redirect")
        @RequiresPayment(price = PRICE, paymentFlow = PaymentFlow.UPFRONT)
        void sendRedirect(HttpServletResponse response) throws IOException {
            response.sendRedirect("/upfront/ok");
        }
    }

    static final class Recorder {
        static final List<X402PaymentSettledEvent> settled = Collections.synchronizedList(new ArrayList<>());
        static final List<X402PaymentFailedEvent> failed = Collections.synchronizedList(new ArrayList<>());
        static final List<X402PaidRequestFailedEvent> paidFailed = Collections.synchronizedList(new ArrayList<>());
        static volatile boolean throwOnPaidFailed;

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
            if (throwOnPaidFailed) {
                throw new IllegalStateException("listener failure");
            }
        }
    }
}
