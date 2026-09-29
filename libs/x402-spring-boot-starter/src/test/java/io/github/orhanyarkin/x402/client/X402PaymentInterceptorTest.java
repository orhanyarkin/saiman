package io.github.orhanyarkin.x402.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.ExactEvmPayload;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.ResourceInfo;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Verifies {@link X402PaymentInterceptor} end to end against a {@link MockRestServiceServer} bound
 * to a real {@link RestClient}: the interceptor sits above the mock transport exactly as it would
 * above a real one, so retries, headers and signatures are exercised for real.
 *
 * <p>Redirect-following behaviour (Javadoc's "Redirects" section) needs a real {@code
 * ClientHttpRequestFactory} and two real local servers, so it's covered separately in {@link
 * X402PaymentInterceptorRedirectTest}, not here.
 */
@ExtendWith(OutputCaptureExtension.class)
class X402PaymentInterceptorTest {

    // The well-known "cow" test private key (keccak256("cow")); see PrivateKeyPaymentSignerTest.
    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";
    private static final String OTHER_PAY_TO = "0x1111111111111111111111111111111111111A";
    // A well-formed (0x + 64 hex chars) transaction hash: X402PaymentInterceptor requires one
    // matching that shape before it will commit a settlement.
    private static final String TX_HASH = "0x" + "ab".repeat(32);
    // https, not plain http: this starter refuses to pay over anything else that isn't loopback
    // (see refusesToPayOverPlainHttpUnlessLoopback below); MockRestServiceServer only ever matches
    // on the URI string, never actually connects, so the scheme here is otherwise arbitrary.
    private static final String RESOURCE_URL = "https://seller.example/v1/resource";
    private static final String AMOUNT = "1000";

    private final X402Codec codec = new X402Codec();
    private final PaymentSigner realSigner = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY);
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
    }

    private MockRestServiceServer bindServer(X402PaymentInterceptor interceptor) {
        restClientBuilder.requestInterceptor(interceptor);
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        return server;
    }

    private PaymentRequirements offer(String payTo, String amount) {
        return offer(payTo, amount, 60);
    }

    private PaymentRequirements offer(String payTo, String amount, int maxTimeoutSeconds) {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                amount,
                TestnetAssets.USDC_ADDRESS,
                payTo,
                maxTimeoutSeconds,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
    }

    private String encodedPaymentRequired(PaymentRequirements... accepts) {
        PaymentRequired paymentRequired = new PaymentRequired(
                2,
                "payment required",
                new ResourceInfo(RESOURCE_URL, null, null, null, null, null),
                List.of(accepts),
                null);
        return codec.encodePaymentRequired(paymentRequired);
    }

    private String encodedSettlementResponse(boolean success, String payer) {
        SettlementResponse settlement = new SettlementResponse(
                success, null, null, payer, TX_HASH, TestnetAssets.NETWORK, AMOUNT, null, null, null);
        return codec.encodeSettlementResponse(settlement);
    }

    /**
     * @param transaction {@code null} to omit the field from the wire entirely (the codec's
     *     NON_NULL inclusion means a null-transaction {@link SettlementResponse} round-trips as a
     *     missing JSON property, exactly like a server that elides it), or any string to send it
     *     verbatim, well-formed or not
     */
    private String encodedSettlementResponse(boolean success, String payer, String transaction) {
        SettlementResponse settlement = new SettlementResponse(
                success, null, null, payer, transaction, TestnetAssets.NETWORK, AMOUNT, null, null, null);
        return codec.encodeSettlementResponse(settlement);
    }

    private X402PaymentInterceptor interceptor(
            PaymentSigner signer, SpendGuard spendGuard, long maxAmount, String... allowedPayTo) {
        return new X402PaymentInterceptor(
                signer, spendGuard, codec, maxAmount, List.of(allowedPayTo), ObservationRegistry.NOOP, fixedClock);
    }

    @SuppressWarnings("unchecked")
    private SpendGuard passthroughSpendGuard() {
        SpendGuard spendGuard = mock(SpendGuard.class);
        when(spendGuard.reserve(any())).thenAnswer(invocation -> {
            PaymentIntent intent = invocation.getArgument(0);
            return new SpendReservation(intent.idempotencyKey(), intent);
        });
        return spendGuard;
    }

    @Test
    void paysOn402AndCommitsOnSuccessfulSettlement() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Idempotency-Key", "req-1"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));

        server.expect(requestTo(RESOURCE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Idempotency-Key", "req-1"))
                .andExpect(request -> {
                    String headerValue = request.getHeaders().getFirst(X402Headers.PAYMENT_SIGNATURE);
                    assertThat(headerValue).isNotNull();
                    PaymentPayload payload = codec.decodePaymentPayload(headerValue);
                    assertThat(payload.accepted()).isEqualTo(offer(PAY_TO, AMOUNT));
                    ExactEvmPayload exact = payload.payload();
                    Eip3009Authorization authorization = exact.authorization();
                    assertThat(authorization.to()).isEqualToIgnoringCase(PAY_TO);
                    assertThat(authorization.value()).isEqualTo(AMOUNT);
                    assertThat(Eip3009TypedData.verify(authorization, exact.signature()))
                            .isTrue();
                    assertThat(Eip3009TypedData.recoverSigner(authorization, exact.signature()))
                            .isEqualToIgnoringCase(realSigner.address());
                })
                .andRespond(withSuccess("{\"summary\":\"ok\"}", org.springframework.http.MediaType.APPLICATION_JSON)
                        .header(X402Headers.PAYMENT_RESPONSE, encodedSettlementResponse(true, realSigner.address())));

        RestClient.ResponseSpec response = restClientBuilder
                .build()
                .get()
                .uri(RESOURCE_URL)
                .header("Idempotency-Key", "req-1")
                .retrieve();

        assertThat(response.toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        server.verify();
        verify(spendGuard).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
    }

    @Test
    void spendGuardDenialHappensBeforeSigningAndSendsNoRetry() {
        SpendGuard spendGuard = mock(SpendGuard.class);
        when(spendGuard.reserve(any())).thenThrow(new SpendDeniedException("over budget"));
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-2")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(SpendDeniedException.class);

        server.verify();
        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void missingIdempotencyKeyIsRefusedBeforeAnyNetworkCall() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);
        // No expectations registered: MockRestServiceServer fails any request that is actually sent.

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class);

        server.verify();
    }

    @Test
    void rejectsOfferWithPayToOutsideTheAllowlistWithoutSigning(CapturedOutput output) throws IOException {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(OTHER_PAY_TO, AMOUNT))));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-3")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(OTHER_PAY_TO));

        assertThat(output.getOut()).doesNotContain(OTHER_PAY_TO);
        assertThat(output.getErr()).doesNotContain(OTHER_PAY_TO);
        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void rejectsOfferAboveTheConfiguredMaximumWithoutSigning() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 500, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-4")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class);

        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void rejectsUnsupportedNetworkWithoutSigning() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        PaymentRequirements unsupportedNetwork = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                "eip155:1",
                AMOUNT,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(unsupportedNetwork)));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-5")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class);

        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void offerWithNonPositiveMaxTimeoutSecondsIsRejectedWithoutSigning() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT, 0))));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-5b")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class);

        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void firstAcceptableOfferAmongMultipleWins() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        // The first offer is unacceptable (payTo outside the allowlist); the second is fine.
        PaymentRequirements unacceptable = offer(OTHER_PAY_TO, AMOUNT);
        PaymentRequirements acceptable = offer(PAY_TO, AMOUNT);
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(unacceptable, acceptable)));
        server.expect(requestTo(RESOURCE_URL))
                .andExpect(request -> {
                    String headerValue = request.getHeaders().getFirst(X402Headers.PAYMENT_SIGNATURE);
                    PaymentPayload payload = codec.decodePaymentPayload(headerValue);
                    assertThat(payload.accepted()).isEqualTo(acceptable);
                })
                .andRespond(withSuccess("ok", org.springframework.http.MediaType.TEXT_PLAIN)
                        .header(X402Headers.PAYMENT_RESPONSE, encodedSettlementResponse(true, realSigner.address())));

        restClientBuilder
                .build()
                .get()
                .uri(RESOURCE_URL)
                .header("Idempotency-Key", "req-multi")
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }

    @Test
    void postBodyIsResentUnchangedOnRetry() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        String requestBody = "{\"query\":\"unchanged\"}";
        server.expect(requestTo(RESOURCE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(requestBody))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        server.expect(requestTo(RESOURCE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(requestBody))
                .andRespond(withSuccess("ok", org.springframework.http.MediaType.TEXT_PLAIN)
                        .header(X402Headers.PAYMENT_RESPONSE, encodedSettlementResponse(true, realSigner.address())));

        restClientBuilder
                .build()
                .post()
                .uri(RESOURCE_URL)
                .header("Idempotency-Key", "req-post")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toBodilessEntity();

        server.verify();
    }

    @Test
    void fourZeroTwoWithoutPaymentRequiredHeaderIsRejectedWithoutSigning() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-no-header")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class);

        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void fourZeroTwoWithUndecodablePaymentRequiredHeaderIsRejectedWithoutSigning() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, "not-valid-base64!!"));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-bad-header")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class);

        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void retryAnswered402KeepsReservation() {
        SpendGuard spendGuard = spy(new PropertiesSpendGuard(5000, List.of(PAY_TO)));
        PaymentSigner signer = spy(realSigner);
        X402PaymentInterceptor interceptor = new X402PaymentInterceptor(
                signer, spendGuard, codec, 5000, List.of(PAY_TO), ObservationRegistry.NOOP, fixedClock);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        // A hostile (or merely failing) seller answers 402 on the paid retry too, even though the
        // signature it just received is still a valid, settleable authorization until validBefore.
        server.expect(requestTo(RESOURCE_URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-6")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentDeclinedAfterSigningException.class)
                .satisfies(e -> assertThat(((PaymentDeclinedAfterSigningException) e).statusCode())
                        .isEqualTo(402));

        server.verify();
        verify(signer, times(1)).signTransferWithAuthorization(any());
        verify(spendGuard, never()).release(any(), any());
        verify(spendGuard, never()).commit(any(), any());

        // The reservation is still held: a further attempt with the same idempotency key -- even
        // for a brand new intent -- must be refused, since the earlier signature could still be
        // settled by this (or a colluding) server.
        PaymentIntent repeat = new PaymentIntent("req-6", URI.create(RESOURCE_URL), offer(PAY_TO, AMOUNT));
        assertThatThrownBy(() -> spendGuard.reserve(repeat)).isInstanceOf(SpendDeniedException.class);
    }

    @Test
    void retryReturning2xxWithoutSettlementHeaderIsAmbiguous() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withSuccess("ok", org.springframework.http.MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-ambig-1")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
    }

    @Test
    void retryReturning2xxWithUnsuccessfulSettlementIsAmbiguous() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withSuccess("ok", org.springframework.http.MediaType.TEXT_PLAIN)
                        .header(X402Headers.PAYMENT_RESPONSE, encodedSettlementResponse(false, realSigner.address())));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-ambig-2")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
    }

    @Test
    void retryReturning2xxWithMissingTransactionIsAmbiguousAndClosesTheResponse() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        ResponseCloseTracker closeTracker = new ResponseCloseTracker();
        restClientBuilder.requestInterceptor(interceptor).requestInterceptor(closeTracker);
        server = MockRestServiceServer.bindTo(restClientBuilder).build();

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        // success=true but the wire omits `transaction` entirely -- decodes to a null String, not
        // just an empty one; this used to NPE inside the tx-hash regex match before it could even
        // decide whether to commit.
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withSuccess("ok", org.springframework.http.MediaType.TEXT_PLAIN)
                        .header(
                                X402Headers.PAYMENT_RESPONSE,
                                encodedSettlementResponse(true, realSigner.address(), null)));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-missing-tx")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
        assertThat(closeTracker.secondResponseWasClosed()).isTrue();
    }

    @Test
    void retryReturning2xxWithMalformedTransactionIsAmbiguousAndClosesTheResponse() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        ResponseCloseTracker closeTracker = new ResponseCloseTracker();
        restClientBuilder.requestInterceptor(interceptor).requestInterceptor(closeTracker);
        server = MockRestServiceServer.bindTo(restClientBuilder).build();

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withSuccess("ok", org.springframework.http.MediaType.TEXT_PLAIN)
                        .header(
                                X402Headers.PAYMENT_RESPONSE,
                                encodedSettlementResponse(true, realSigner.address(), "not-a-transaction-hash")));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-malformed-tx")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
        assertThat(closeTracker.secondResponseWasClosed()).isTrue();
    }

    @Test
    void retryReturning5xxIsAmbiguous() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        server.expect(requestTo(RESOURCE_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-ambig-3")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
    }

    @Test
    void retryReturningNonPaymentFourXxIsAmbiguous() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        server.expect(requestTo(RESOURCE_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-ambig-4")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
    }

    @Test
    void ioFailureAfterPayingLeavesTheReservationInPlace() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        server.expect(requestTo(RESOURCE_URL)).andRespond(withException(new IOException("simulated network failure")));

        // RestClient wraps the interceptor's propagated IOException in a ResourceAccessException;
        // the interceptor itself never catches it (see X402PaymentInterceptor's Javadoc: an I/O
        // failure on the retry leaves the reservation in place rather than releasing it).
        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-7")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                .hasCauseInstanceOf(IOException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
    }

    @Test
    void signingFailureAfterReserveReleasesTheReservation() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner throwingSigner = mock(PaymentSigner.class);
        when(throwingSigner.address()).thenReturn(realSigner.address());
        when(throwingSigner.signTransferWithAuthorization(any())).thenThrow(new RuntimeException("HSM unavailable"));
        X402PaymentInterceptor interceptor = interceptor(throwingSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));
        // No second expectation: nothing must be sent after a signing failure.

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-sign-fail")
                        .retrieve()
                        .toBodilessEntity())
                .hasMessageContaining("HSM unavailable");

        server.verify();
        verify(spendGuard).release(any(), org.mockito.ArgumentMatchers.eq("signing failed"));
        verify(spendGuard, never()).commit(any(), any());
    }

    @Test
    void nonPaymentResponsesPassThroughUnchanged() {
        SpendGuard spendGuard = passthroughSpendGuard();
        X402PaymentInterceptor interceptor = interceptor(realSigner, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withSuccess("ok", org.springframework.http.MediaType.TEXT_PLAIN));

        var entity = restClientBuilder
                .build()
                .get()
                .uri(RESOURCE_URL)
                .header("Idempotency-Key", "req-8")
                .retrieve()
                .toBodilessEntity();

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        server.verify();
        verify(spendGuard, never()).reserve(any());
    }

    @Test
    void requestAlreadyCarryingPaymentSignatureIsPassedThroughUnchanged() {
        SpendGuard spendGuard = mock(SpendGuard.class);
        PaymentSigner signer = mock(PaymentSigner.class);
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        // No Idempotency-Key either: a caller-supplied PAYMENT-SIGNATURE means this interceptor
        // does nothing at all, so it must not even enforce that requirement.
        server.expect(requestTo(RESOURCE_URL))
                .andExpect(header(X402Headers.PAYMENT_SIGNATURE, "caller-supplied-value"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header(X402Headers.PAYMENT_SIGNATURE, "caller-supplied-value")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(e -> assertThat(((RestClientResponseException) e)
                                .getStatusCode()
                                .value())
                        .isEqualTo(402));

        server.verify();
        verify(spendGuard, never()).reserve(any());
        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void refusesToPayOverPlainHttpUnlessLoopback() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        String insecureUrl = "http://seller.example/v1/resource";
        restClientBuilder.requestInterceptor(interceptor);
        MockRestServiceServer insecureServer =
                MockRestServiceServer.bindTo(restClientBuilder).build();

        insecureServer
                .expect(requestTo(insecureUrl))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT))));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(insecureUrl)
                        .header("Idempotency-Key", "req-insecure")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentRejectedException.class);

        verify(spendGuard, never()).reserve(any());
        verify(signer, never()).signTransferWithAuthorization(any());
    }

    @Test
    void timeWindowUsesTheFixedClockExactly() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = spy(realSigner);
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT, 60))));
        server.expect(requestTo(RESOURCE_URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-time-1")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentDeclinedAfterSigningException.class);

        org.mockito.ArgumentCaptor<Eip3009Authorization> captor =
                org.mockito.ArgumentCaptor.forClass(Eip3009Authorization.class);
        verify(signer).signTransferWithAuthorization(captor.capture());
        long now = fixedClock.instant().getEpochSecond();
        assertThat(captor.getValue().validAfter()).isEqualTo(Long.toString(now - 600));
        assertThat(captor.getValue().validBefore()).isEqualTo(Long.toString(now + 60));
    }

    @Test
    void maxTimeoutSecondsAbove60ClampsValidBeforeTo60Seconds() {
        SpendGuard spendGuard = passthroughSpendGuard();
        PaymentSigner signer = spy(realSigner);
        X402PaymentInterceptor interceptor = interceptor(signer, spendGuard, 5000, PAY_TO);
        bindServer(interceptor);

        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired(offer(PAY_TO, AMOUNT, 86400))));
        server.expect(requestTo(RESOURCE_URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> restClientBuilder
                        .build()
                        .get()
                        .uri(RESOURCE_URL)
                        .header("Idempotency-Key", "req-time-2")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(PaymentDeclinedAfterSigningException.class);

        org.mockito.ArgumentCaptor<Eip3009Authorization> captor =
                org.mockito.ArgumentCaptor.forClass(Eip3009Authorization.class);
        verify(signer).signTransferWithAuthorization(captor.capture());
        long now = fixedClock.instant().getEpochSecond();
        assertThat(captor.getValue().validBefore()).isEqualTo(Long.toString(now + 60));
    }

    /**
     * Registered after {@link X402PaymentInterceptor} on the same builder, so it wraps the
     * response closer to the transport and observes whether the interceptor called {@code
     * close()} on the <em>second</em> (paid-retry) response -- {@code ClientHttpResponse} itself
     * exposes no other way to ask "was this closed".
     */
    private static final class ResponseCloseTracker
            implements org.springframework.http.client.ClientHttpRequestInterceptor {

        private final java.util.concurrent.atomic.AtomicInteger callCount =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean secondResponseClosed =
                new java.util.concurrent.atomic.AtomicBoolean();

        boolean secondResponseWasClosed() {
            return secondResponseClosed.get();
        }

        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request,
                byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution)
                throws IOException {
            org.springframework.http.client.ClientHttpResponse response = execution.execute(request, body);
            if (callCount.incrementAndGet() != 2) {
                return response;
            }
            return new org.springframework.http.client.ClientHttpResponse() {
                @Override
                public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                    return response.getStatusCode();
                }

                @Override
                public String getStatusText() throws IOException {
                    return response.getStatusText();
                }

                @Override
                public org.springframework.http.HttpHeaders getHeaders() {
                    return response.getHeaders();
                }

                @Override
                public java.io.InputStream getBody() throws IOException {
                    return response.getBody();
                }

                @Override
                public void close() {
                    secondResponseClosed.set(true);
                    response.close();
                }
            };
        }
    }
}
