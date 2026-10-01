package io.github.orhanyarkin.x402.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.ResourceInfo;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The client side of x402 v2 {@code extra.paymentFlow} (ADR-0021): an {@code upfront} offer is
 * payable, {@code authorization} is preferred when both are offered, any other flow is refused
 * before signing, and the commit rule does not depend on the flow.
 */
class X402PaymentInterceptorPaymentFlowTest {

    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";
    private static final String TX_HASH = "0x" + "cd".repeat(32);
    private static final String RESOURCE_URL = "https://seller.example/v1/resource";
    private static final String AMOUNT = "1000";

    private final X402Codec codec = new X402Codec();
    private final PaymentSigner realSigner = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY);
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private SpendGuard spendGuard;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        spendGuard = mock(SpendGuard.class);
        when(spendGuard.reserve(any())).thenAnswer(invocation -> {
            PaymentIntent intent = invocation.getArgument(0);
            return new SpendReservation(intent.idempotencyKey(), intent);
        });
        restClientBuilder.requestInterceptor(new X402PaymentInterceptor(
                realSigner, spendGuard, codec, 5000, List.of(PAY_TO), ObservationRegistry.NOOP, fixedClock));
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private static PaymentRequirements offer(String amount, @Nullable String paymentFlow) {
        Map<String, Object> extra = paymentFlow == null
                ? Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION)
                : Map.of(
                        "name",
                        TestnetAssets.USDC_NAME,
                        "version",
                        TestnetAssets.USDC_VERSION,
                        "paymentFlow",
                        paymentFlow);
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                amount,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                extra);
    }

    private String paymentRequired(PaymentRequirements... accepts) {
        return codec.encodePaymentRequired(new PaymentRequired(
                2,
                "payment required",
                new ResourceInfo(RESOURCE_URL, null, null, null, null, null),
                List.of(accepts),
                null));
    }

    private String settlementResponse() {
        return codec.encodeSettlementResponse(new SettlementResponse(
                true, null, null, realSigner.address(), TX_HASH, TestnetAssets.NETWORK, AMOUNT, null, null, null));
    }

    /** Expects the paid retry and captures the offer the client says it accepted. */
    private AtomicReference<PaymentRequirements> expectPaidRetry(HttpStatus status) {
        AtomicReference<PaymentRequirements> accepted = new AtomicReference<>();
        server.expect(requestTo(RESOURCE_URL))
                .andExpect(request -> {
                    String header = request.getHeaders().getFirst(X402Headers.PAYMENT_SIGNATURE);
                    assertThat(header).isNotNull();
                    PaymentPayload payload = codec.decodePaymentPayload(header);
                    accepted.set(payload.accepted());
                })
                .andRespond(
                        status.is2xxSuccessful()
                                ? withSuccess("{}", MediaType.APPLICATION_JSON)
                                        .header(X402Headers.PAYMENT_RESPONSE, settlementResponse())
                                : withStatus(status).header(X402Headers.PAYMENT_RESPONSE, settlementResponse()));
        return accepted;
    }

    private void get(String idempotencyKey) {
        restClientBuilder
                .build()
                .get()
                .uri(RESOURCE_URL)
                .header("Idempotency-Key", idempotencyKey)
                .retrieve()
                .toBodilessEntity();
    }

    @Test
    void anUpfrontOfferIsPaidWithTheOfferEchoedVerbatim() {
        PaymentRequirements upfront = offer(AMOUNT, "upfront");
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, paymentRequired(upfront)));
        AtomicReference<PaymentRequirements> accepted = expectPaidRetry(HttpStatus.OK);

        get("flow-1");

        server.verify();
        assertThat(accepted.get()).isEqualTo(upfront);
        assertThat(accepted.get().extraString("paymentFlow")).isEqualTo("upfront");
        verify(spendGuard).commit(any(), any());
    }

    @Test
    void authorizationIsPreferredWhenBothFlowsAreOffered() {
        PaymentRequirements upfront = offer(AMOUNT, "upfront");
        PaymentRequirements authorization = offer(AMOUNT, null);
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, paymentRequired(upfront, authorization)));
        AtomicReference<PaymentRequirements> accepted = expectPaidRetry(HttpStatus.OK);

        get("flow-2");

        assertThat(accepted.get()).isEqualTo(authorization);
    }

    @Test
    void anExplicitAuthorizationFlowIsAlsoPreferredOverUpfront() {
        PaymentRequirements upfront = offer(AMOUNT, "upfront");
        PaymentRequirements authorization = offer(AMOUNT, "authorization");
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, paymentRequired(upfront, authorization)));
        AtomicReference<PaymentRequirements> accepted = expectPaidRetry(HttpStatus.OK);

        get("flow-3");

        assertThat(accepted.get()).isEqualTo(authorization);
    }

    @Test
    void anUnknownFlowIsRefusedBeforeSigning() {
        PaymentSigner signer = mock(PaymentSigner.class);
        when(signer.address()).thenReturn(realSigner.address());
        restClientBuilder = RestClient.builder();
        restClientBuilder.requestInterceptor(new X402PaymentInterceptor(
                signer, spendGuard, codec, 5000, List.of(PAY_TO), ObservationRegistry.NOOP, fixedClock));
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, paymentRequired(offer(AMOUNT, "escrow"))));

        assertThatThrownBy(() -> get("flow-4")).isInstanceOf(PaymentRejectedException.class);

        server.verify();
        verify(signer, never()).signTransferWithAuthorization(any());
        verify(spendGuard, never()).reserve(any());
    }

    @Test
    void aPaidUpfrontRequestThatFailsIsAmbiguousEvenWithAPaymentResponse() {
        server.expect(requestTo(RESOURCE_URL))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .header(X402Headers.PAYMENT_REQUIRED, paymentRequired(offer(AMOUNT, "upfront"))));
        expectPaidRetry(HttpStatus.SERVICE_UNAVAILABLE);

        assertThatThrownBy(() -> get("flow-5")).isInstanceOf(AmbiguousPaymentException.class);

        verify(spendGuard, never()).commit(any(), any());
        verify(spendGuard, never()).release(any(), any());
    }
}
