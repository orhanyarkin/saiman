package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MockMvc/WebMvc acceptance test: a real {@code @RequiresPayment} endpoint behind {@link
 * RequiresPaymentInterceptor} and {@link X402SettlementFilter}, paid against a {@link
 * FakeFacilitator}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = RequiresPaymentIntegrationTests.TestApplication.class)
@AutoConfigureRestTestClient
class RequiresPaymentIntegrationTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();
    private static final String PAY_TO = TestWallets.OTHER_PAYER.address();
    private static final String PRICE = "10000";

    @DynamicPropertySource
    static void x402Properties(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> PAY_TO);
        registry.add("x402.server.facilitator.url", FACILITATOR::url);
        // The embedded server's own default max request header size (~8KB) is well below
        // X402Codec.MAX_ENCODED_CHARS (~21KB): raised here so the oversized-header test exercises
        // this starter's own 402 rejection rather than a 400 from the container before the
        // request ever reaches it.
        registry.add("server.max-http-request-header-size", () -> "48KB");
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
    void missingHeaderReturns402WithDecodablePaymentRequired() {
        ExchangeResult result = client.get()
                .uri("/paid/ok")
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
        assertThat(paymentRequired.accepts()).containsExactly(offer());
    }

    @Test
    void validPaymentSucceedsAndCarriesPaymentResponseHeader() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectBody(String.class)
                .isEqualTo("paid content");

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void replayOfTheSameHeaderIsRejected() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isOk();

        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void replayWithUpperCasedFromAndNonceIsRejected() {
        Eip3009Authorization authorization =
                PaymentPayloads.authorizationFor(TestWallets.PAYER, offer(), Instant.now());
        PaymentPayload payload = PaymentPayloads.sign(TestWallets.PAYER, offer(), authorization);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk();

        // Same (from, nonce) pair, only the case differs: canonicalNonceKey() must still collide.
        Eip3009Authorization upperCased = new Eip3009Authorization(
                authorization.from().toUpperCase(java.util.Locale.ROOT).replace("0X", "0x"),
                authorization.to(),
                authorization.value(),
                authorization.validAfter(),
                authorization.validBefore(),
                authorization.nonce().toUpperCase(java.util.Locale.ROOT).replace("0X", "0x"));
        PaymentPayload replay = PaymentPayloads.sign(TestWallets.PAYER, offer(), upperCased);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, replay))
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void concurrentReplayResultsInExactlyOneSuccessAndOneSettleCall() throws InterruptedException {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        try {
            for (int i = 0; i < threadCount; i++) {
                var _ = pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    int status = client.get()
                            .uri("/paid/ok")
                            .header(X402Headers.PAYMENT_SIGNATURE, header)
                            .exchange()
                            .returnResult()
                            .getStatus()
                            .value();
                    if (status == 200) {
                        successCount.incrementAndGet();
                    }
                });
            }
            ready.await();
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void settleFailureReturns402AndDoesNotLeakTheHandlerBody() {
        FACILITATOR.injectSettleFailure("insufficient_funds");
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        String body = client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().doesNotContain("paid content");
    }

    @Test
    void settleSuccessWithoutAWellFormedTransactionIsTreatedAsAmbiguousNotCharged() {
        // M1-A: a facilitator answering "success" with no (or a malformed) transaction hash must
        // never be treated as a real settlement -- the field isn't validated by the wire codec
        // (it's a facilitator-response record, not client input), so this is the server's own
        // last line of defence against flushing paid content for an unconfirmed payment.
        RecordingEventListener.settled.clear();
        RecordingEventListener.failed.clear();
        FACILITATOR.injectSettleSuccessWithoutTransaction();
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        String body = client.get()
                .uri("/paid/leaky")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .expectHeader()
                .doesNotExist(X402Headers.PAYMENT_RESPONSE)
                .expectHeader()
                .doesNotExist("X-Download-Url")
                .expectHeader()
                .doesNotExist("Set-Cookie")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().doesNotContain("paid content");
        assertThat(RecordingEventListener.settled).isEmpty();
        assertThat(RecordingEventListener.failed).hasSize(1);
        assertThat(RecordingEventListener.failed.get(0).errorReason()).isEqualTo("ambiguous");

        long verifyCallsBeforeReplay = FACILITATOR.verifyCallCount();
        long settleCallsBeforeReplay = FACILITATOR.settleCallCount();

        // The nonce claim was NOT released (an ambiguous outcome, not a rejection): the same
        // authorization replayed is refused before it ever reaches the facilitator again.
        client.get()
                .uri("/paid/leaky")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(verifyCallsBeforeReplay);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(settleCallsBeforeReplay);
    }

    @Test
    void handlerRejectionIsNeverSettledAndReleasesTheNonceClaim() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        client.get()
                .uri("/paid/not-found")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isNotFound();

        assertThat(FACILITATOR.settleCallCount()).isZero();

        // The nonce claim was released: the same authorization can still be used successfully.
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void malformedHeaderReturns402NotAServerError() {
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, "not-valid-base64!!")
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void oversizedHeaderReturns402NotAServerError() {
        String oversized = "A".repeat(X402Codec.MAX_ENCODED_CHARS + 4);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, oversized)
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void wrongAmountReturns402() {
        Eip3009Authorization authorization =
                PaymentPayloads.authorizationFor(TestWallets.PAYER, offer(), Instant.now());
        Eip3009Authorization wrongAmount = new Eip3009Authorization(
                authorization.from(),
                authorization.to(),
                "1",
                authorization.validAfter(),
                authorization.validBefore(),
                authorization.nonce());
        PaymentPayload payload = PaymentPayloads.sign(TestWallets.PAYER, offer(), wrongAmount);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void wrongPayToReturns402() {
        Eip3009Authorization authorization =
                PaymentPayloads.authorizationFor(TestWallets.PAYER, offer(), Instant.now());
        Eip3009Authorization wrongPayTo = new Eip3009Authorization(
                authorization.from(),
                TestWallets.PAYER.address(),
                authorization.value(),
                authorization.validAfter(),
                authorization.validBefore(),
                authorization.nonce());
        PaymentPayload payload = PaymentPayloads.sign(TestWallets.PAYER, offer(), wrongPayTo);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void expiredAuthorizationReturns402() {
        Instant past = Instant.now().minusSeconds(3600);
        Eip3009Authorization authorization = PaymentPayloads.authorizationFor(TestWallets.PAYER, offer(), past);
        PaymentPayload payload = PaymentPayloads.sign(TestWallets.PAYER, offer(), authorization);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void windowShorterThanTheFacilitatorReadTimeoutMarginReturns402() {
        // Default facilitator read-timeout is 15s, so a 10s window is inside the offer's own
        // maxTimeoutSeconds (60s, not "too large") but below the 15s+5s margin ("too short" --
        // settlement could otherwise lose a race against the authorization's own expiry).
        long now = Instant.now().getEpochSecond();
        Eip3009Authorization tooShort = new Eip3009Authorization(
                TestWallets.PAYER.address(),
                offer().payTo(),
                offer().amount(),
                Long.toString(now - 5),
                Long.toString(now + 10),
                Eip3009TypedData.randomNonce());
        PaymentPayload payload = PaymentPayloads.sign(TestWallets.PAYER, offer(), tooShort);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void redirectFromAPaidHandlerIsNeverCharged() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid/redirect")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .is3xxRedirection();

        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void wrongNetworkReturns402() {
        PaymentRequirements wrongNetworkOffer = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                "eip155:1",
                PRICE,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                properties.maxTimeoutSeconds(),
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        Eip3009Authorization authorization =
                PaymentPayloads.authorizationFor(TestWallets.PAYER, offer(), Instant.now());
        String signature = TestWallets.PAYER.signTransferWithAuthorization(authorization);
        PaymentPayload payload = new PaymentPayload(
                2,
                null,
                wrongNetworkOffer,
                new io.github.orhanyarkin.x402.core.ExactEvmPayload(signature, authorization),
                null);
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void paymentResponseIsMinimalServerBuiltAndUnder1Kb() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        ExchangeResult result = client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult();

        String header = result.getResponseHeaders().getFirst(X402Headers.PAYMENT_RESPONSE);
        assertThat(header).isNotNull();
        assertThat(header.length()).isLessThan(1024);
        io.github.orhanyarkin.x402.core.SettlementResponse settlement = codec.decodeSettlementResponse(header);
        assertThat(settlement.success()).isTrue();
        assertThat(settlement.network()).isEqualTo(offer().network());
        assertThat(settlement.amount()).isEqualTo(offer().amount());
        assertThat(settlement.transaction()).matches("0x[0-9a-fA-F]{64}");
        assertThat(settlement.extensions()).isNull();
        assertThat(settlement.extra()).isNull();
        assertThat(settlement.extensionResponses()).isNull();
    }

    @Test
    void hostHeaderIsNeverReflectedIntoTheResourceUrlSentToTheFacilitator() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid/ok")
                .header("Host", "evil.example")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk();

        String resourceUrl = FACILITATOR.lastVerifyResourceUrl();
        assertThat(resourceUrl).isNotNull();
        assertThat(resourceUrl).doesNotContain("evil.example").isEqualTo("/paid/ok");
    }

    @Test
    void settleFailureRestoresOuterHeadersAndDropsHandlerSetHeaders() {
        FACILITATOR.injectSettleFailure("insufficient_funds");
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid/leaky")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .expectHeader()
                .valueEquals("X-Outer-Header", "outer-value")
                .expectHeader()
                .doesNotExist("X-Download-Url")
                .expectHeader()
                .doesNotExist("Set-Cookie");
    }

    @Test
    void headWithoutPaymentReturns402() {
        client.method(org.springframework.http.HttpMethod.HEAD)
                .uri("/paid/ok")
                .exchange()
                .expectStatus()
                .isEqualTo(402);
    }

    @Test
    void optionsIsNeverPaymentChecked() {
        // OPTIONS on an @RequestMapping-annotated method resolves to Spring MVC's own built-in
        // OPTIONS handling (an internal HandlerMethod, not the annotated one), so
        // RequiresPaymentRegistry never has an entry for it: it is treated as "not a paid
        // handler" and passed straight through, unpaid and with the actual handler never invoked.
        client.method(org.springframework.http.HttpMethod.OPTIONS)
                .uri("/paid/ok")
                .exchange()
                .expectStatus()
                .is2xxSuccessful()
                .expectHeader()
                .exists("Allow");
    }

    @Test
    void handlerCallingFlushBufferStillGetsSwappedOutOnSettleFailure() {
        FACILITATOR.injectSettleFailure("insufficient_funds");
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        String body = client.get()
                .uri("/paid/flushing")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isEqualTo(402)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull().doesNotContain("paid content");
    }

    @Test
    void settledAndFailedEventsArePublished() {
        RecordingEventListener.settled.clear();
        RecordingEventListener.failed.clear();

        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(RecordingEventListener.settled).hasSize(1);
        X402PaymentSettledEvent settledEvent = RecordingEventListener.settled.get(0);
        assertThat(settledEvent.from()).isEqualTo(TestWallets.PAYER.address());
        assertThat(settledEvent.transactionHash()).matches("0x[0-9a-fA-F]{64}");

        FACILITATOR.injectSettleFailure("insufficient_funds");
        PaymentPayload failingPayload = PaymentPayloads.build(TestWallets.PAYER, offer());
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, failingPayload))
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        assertThat(RecordingEventListener.failed).hasSize(1);
        assertThat(RecordingEventListener.failed.get(0).errorReason()).isEqualTo("insufficient_funds");
    }

    @Test
    void workDone422KeepsTheClaimSoTheSameAuthorizationCannotBeReplayed() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);
        PaidTestController.workRuns.set(0);

        client.get()
                .uri("/paid/work-done-422")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(422);
        client.get()
                .uri("/paid/work-done-422")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        assertThat(PaidTestController.workRuns.get()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void non2xxWithoutTheWorkDoneMarkStillReleasesTheClaim() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());
        String header = PaymentPayloads.header(codec, payload);

        client.get()
                .uri("/paid/plain-422")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(422);
        client.get()
                .uri("/paid/plain-422")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(422);
    }

    @Test
    void handlerSeesTheRecoveredPayerThroughThePaymentContext() {
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid/who")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo(TestWallets.PAYER.address());
    }

    @Test
    void handlerSeesTheVerifiedValidBeforeThroughThePaymentContext() {
        long now = Instant.now().getEpochSecond();
        PaymentPayload payload = PaymentPayloads.sign(TestWallets.PAYER, offer(), windowOf(now, 50));

        client.get()
                .uri("/paid/valid-before")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo(Instant.ofEpochSecond(now + 50).toString());

        // No verified payment, no validBefore: an unpaid handler must not see a client's header.
        client.get()
                .uri("/free/valid-before")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .isEqualTo("null");
    }

    @Test
    void windowShorterThanTheHandlersMinWindowIsRejectedWithoutCallingTheFacilitator() {
        long now = Instant.now().getEpochSecond();
        // 30 s is above the starter default (15 + 5) but below the handler's 45 s minimum.
        PaymentPayload shortWindow = PaymentPayloads.sign(TestWallets.PAYER, offer(), windowOf(now, 30));
        long verifyCalls = FACILITATOR.verifyCallCount();

        client.get()
                .uri("/paid/strict-window")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, shortWindow))
                .exchange()
                .expectStatus()
                .isEqualTo(402);
        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(verifyCalls);

        // The same short window is fine on a handler without a minimum, and 55 s passes the strict one.
        client.get()
                .uri("/paid/ok")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, shortWindow))
                .exchange()
                .expectStatus()
                .isOk();
        PaymentPayload longWindow = PaymentPayloads.sign(TestWallets.PAYER, offer(), windowOf(now, 55));
        client.get()
                .uri("/paid/strict-window")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, longWindow))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void unmappedRuntimeExceptionFromAHandlerNeverSettlesAndLeaksNoHandlerHeaders() {
        RecordingEventListener.settled.clear();
        PaymentPayload payload = PaymentPayloads.build(TestWallets.PAYER, offer());

        client.get()
                .uri("/paid/boom")
                .header(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, payload))
                .exchange()
                .expectStatus()
                .is5xxServerError()
                .expectHeader()
                .doesNotExist(X402Headers.PAYMENT_RESPONSE)
                .expectHeader()
                .doesNotExist("X-Download-Url")
                .expectHeader()
                .doesNotExist("Set-Cookie");

        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(RecordingEventListener.settled).isEmpty();
    }

    private Eip3009Authorization windowOf(long now, long seconds) {
        return new Eip3009Authorization(
                TestWallets.PAYER.address(),
                offer().payTo(),
                offer().amount(),
                Long.toString(now - 5),
                Long.toString(now + seconds),
                Eip3009TypedData.randomNonce());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        PaidTestController paidTestController() {
            return new PaidTestController();
        }

        @Bean
        RecordingEventListener recordingEventListener() {
            return new RecordingEventListener();
        }

        /** Simulates an "outer" filter (e.g. CORS) that runs before X402SettlementFilter and sets a header. */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> outerHeaderFilter() {
            org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                            new org.springframework.web.filter.OncePerRequestFilter() {
                                @Override
                                protected void doFilterInternal(
                                        jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain filterChain)
                                        throws jakarta.servlet.ServletException, java.io.IOException {
                                    response.setHeader("X-Outer-Header", "outer-value");
                                    filterChain.doFilter(request, response);
                                }
                            });
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

        // Explicit, deterministic nonce store for this test: since X402ServerAutoConfiguration
        // now orders itself after Boot's DataRedisAutoConfiguration, a StringRedisTemplate bean
        // created here (lazily connecting, even with no Redis server running) would correctly win
        // the RedisNonceStoreConfiguration @ConditionalOnMissingBean race -- and every claim()
        // call would then fail at request time with a connection error. This explicit bean, a
        // user-declared bean rather than an auto-configuration one, is registered before any
        // auto-configuration condition is evaluated, so it always wins regardless. See
        // X402NonceStoreSelectionTests for the Redis-is-actually-chosen-when-available case.
        @Bean
        PaymentNonceStore x402InMemoryPaymentNonceStoreForTests() {
            return new InMemoryPaymentNonceStore();
        }
    }

    @RestController
    static class PaidTestController {

        @GetMapping("/paid/ok")
        @RequiresPayment(price = PRICE, description = "test resource")
        String ok() {
            return "paid content";
        }

        @GetMapping("/paid/not-found")
        @RequiresPayment(price = PRICE)
        org.springframework.http.ResponseEntity<String> notFound() {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body("not found");
        }

        @GetMapping("/paid/redirect")
        @RequiresPayment(price = PRICE)
        org.springframework.http.ResponseEntity<Void> redirect() {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                    .location(java.net.URI.create("/paid/ok"))
                    .build();
        }

        @GetMapping("/paid/leaky")
        @RequiresPayment(price = PRICE)
        String leaky(jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("X-Download-Url", "https://internal.example/secret-file");
            response.addCookie(new jakarta.servlet.http.Cookie("session", "leaked-session-id"));
            return "paid content";
        }

        static final AtomicInteger workRuns = new AtomicInteger();

        @GetMapping("/paid/work-done-422")
        @RequiresPayment(price = PRICE)
        org.springframework.http.ResponseEntity<String> workDone422(jakarta.servlet.http.HttpServletRequest request) {
            workRuns.incrementAndGet();
            X402PaymentContext.markWorkDone(request);
            return org.springframework.http.ResponseEntity.unprocessableEntity().body("no");
        }

        @GetMapping("/paid/plain-422")
        @RequiresPayment(price = PRICE)
        org.springframework.http.ResponseEntity<String> plain422() {
            return org.springframework.http.ResponseEntity.unprocessableEntity().body("no");
        }

        @GetMapping("/paid/who")
        @RequiresPayment(price = PRICE)
        String who(jakarta.servlet.http.HttpServletRequest request) {
            return X402PaymentContext.payer(request);
        }

        @GetMapping("/paid/valid-before")
        @RequiresPayment(price = PRICE)
        String validBefore(jakarta.servlet.http.HttpServletRequest request) {
            return String.valueOf(X402PaymentContext.validBefore(request));
        }

        @GetMapping("/free/valid-before")
        String freeValidBefore(jakarta.servlet.http.HttpServletRequest request) {
            return String.valueOf(X402PaymentContext.validBefore(request));
        }

        @GetMapping("/paid/strict-window")
        @RequiresPayment(price = PRICE, minWindowSeconds = 45)
        String strictWindow() {
            return "paid content";
        }

        @GetMapping("/paid/boom")
        @RequiresPayment(price = PRICE)
        String boom(jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("X-Download-Url", "https://internal.example/secret-file");
            response.addCookie(new jakarta.servlet.http.Cookie("session", "leaked-session-id"));
            throw new IllegalStateException("boom");
        }

        @GetMapping("/paid/flushing")
        @RequiresPayment(price = PRICE)
        void flushing(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
            response.getWriter().write("paid content");
            response.flushBuffer();
        }
    }

    static final class RecordingEventListener {
        static final java.util.List<X402PaymentSettledEvent> settled =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        static final java.util.List<X402PaymentFailedEvent> failed =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @org.springframework.context.event.EventListener
        void onSettled(X402PaymentSettledEvent event) {
            settled.add(event);
        }

        @org.springframework.context.event.EventListener
        void onFailed(X402PaymentFailedEvent event) {
            failed.add(event);
        }
    }
}
