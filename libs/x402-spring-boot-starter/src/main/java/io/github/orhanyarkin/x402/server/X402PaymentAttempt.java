package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.micrometer.observation.Observation;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-request state shared between {@link X402SettlementFilter} and {@link
 * RequiresPaymentInterceptor} through a request attribute, for one {@link RequiresPayment} handler
 * invocation.
 *
 * <p>{@link X402SettlementFilter} creates this (with the started {@link #observation()}) before
 * dispatching, and is the only component that ever calls {@link Observation#stop()} on it -- it is
 * the outermost component in the chain, so it is guaranteed to run to completion whether the
 * interceptor rejects the request, the handler rejects it, or settlement succeeds or fails. {@link
 * RequiresPaymentInterceptor} fills in the rest of the fields as it validates the payment.
 *
 * <p>{@link #interceptorRan()} is set as the very first thing {@link
 * RequiresPaymentInterceptor#preHandle} does once it finds this attribute -- a runtime backstop
 * for {@link X402SettlementFilter}: if it is still {@code false} after dispatch for a request this
 * filter identified as targeting a paid handler, the interceptor never ran at all (e.g. an
 * application overriding {@code WebMvcConfigurationSupport} directly, bypassing {@code
 * WebMvcConfigurer} registration entirely), so nothing actually checked payment and the handler
 * must not be trusted to have been paid for.
 *
 * <p>Package-private: purely an internal wiring detail, not part of this starter's public API.
 */
final class X402PaymentAttempt {

    private final Observation observation;
    private final RequiresPaymentRegistry.Entry entry;
    private final Map<String, List<String>> headerSnapshot;

    private boolean interceptorRan;
    private @Nullable PaymentPayload payload;
    private @Nullable String nonceKey;
    private @Nullable String claimToken;
    private @Nullable String payer;
    private @Nullable String txHash;
    private boolean verified;
    private boolean settleAttempted;
    private @Nullable String paymentResponseHeader;
    private boolean paidFailureReported;
    private volatile boolean workDone;
    private String outcome = "unknown";

    X402PaymentAttempt(
            Observation observation, RequiresPaymentRegistry.Entry entry, Map<String, List<String>> headerSnapshot) {
        this.observation = observation;
        this.entry = entry;
        this.headerSnapshot = headerSnapshot;
    }

    Observation observation() {
        return observation;
    }

    RequiresPaymentRegistry.Entry entry() {
        return entry;
    }

    /**
     * The response headers present before dispatch (set by filters earlier in the chain, e.g.
     * CORS), restored whenever the response is reset to write a payment answer.
     */
    Map<String, List<String>> headerSnapshot() {
        return headerSnapshot;
    }

    /**
     * Records that {@code /settle} was called for this attempt <em>before</em> the handler (the
     * upfront flow), whatever its outcome. From then on the settlement filter must never release the
     * nonce claim: either money moved, or the outcome is ambiguous.
     */
    void markSettleAttempted() {
        this.settleAttempted = true;
    }

    boolean settleAttempted() {
        return settleAttempted;
    }

    /**
     * Records a successful settlement: the transaction hash and the encoded client-facing {@code
     * PAYMENT-RESPONSE} header value.
     */
    void markSettled(String transaction, String encodedPaymentResponse) {
        this.txHash = transaction;
        this.paymentResponseHeader = encodedPaymentResponse;
    }

    /** Whether the payment has been settled successfully (only ever before the handler: upfront). */
    boolean settled() {
        return paymentResponseHeader != null;
    }

    /** Records that {@link X402PaidRequestFailedEvent} was published for this attempt (at most once). */
    void markPaidFailureReported() {
        this.paidFailureReported = true;
    }

    boolean paidFailureReported() {
        return paidFailureReported;
    }

    /** The encoded {@code PAYMENT-RESPONSE} of a successful settlement, or {@code null}. */
    @Nullable
    String paymentResponseHeader() {
        return paymentResponseHeader;
    }

    void markInterceptorRan() {
        this.interceptorRan = true;
    }

    boolean interceptorRan() {
        return interceptorRan;
    }

    void markVerified(PaymentPayload payload, String nonceKey, String claimToken, @Nullable String payer) {
        this.payload = payload;
        this.nonceKey = nonceKey;
        this.claimToken = claimToken;
        this.payer = payer;
        this.verified = true;
    }

    boolean verified() {
        return verified;
    }

    void markWorkDone() {
        this.workDone = true;
    }

    boolean workDone() {
        return workDone;
    }

    /** @throws IllegalStateException if called before {@link #markVerified} */
    PaymentPayload payload() {
        PaymentPayload value = this.payload;
        if (value == null) {
            throw new IllegalStateException("payload accessed before markVerified");
        }
        return value;
    }

    /** @throws IllegalStateException if called before {@link #markVerified} */
    String nonceKey() {
        String value = this.nonceKey;
        if (value == null) {
            throw new IllegalStateException("nonceKey accessed before markVerified");
        }
        return value;
    }

    /** @throws IllegalStateException if called before {@link #markVerified} */
    String claimToken() {
        String value = this.claimToken;
        if (value == null) {
            throw new IllegalStateException("claimToken accessed before markVerified");
        }
        return value;
    }

    @Nullable
    String payer() {
        return payer;
    }

    void payer(@Nullable String payer) {
        this.payer = payer;
    }

    void txHash(@Nullable String txHash) {
        this.txHash = txHash;
    }

    @Nullable
    String txHash() {
        return txHash;
    }

    void outcome(String outcome) {
        this.outcome = outcome;
    }

    String outcome() {
        return outcome;
    }
}
