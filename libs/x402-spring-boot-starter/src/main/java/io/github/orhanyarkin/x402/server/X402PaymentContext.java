package io.github.orhanyarkin.x402.server;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Lets a {@link RequiresPayment} handler talk back to the payment machinery for the current request.
 *
 * <p>In the default {@code authorization} flow the starter serves a resource only after the handler
 * answers 2xx, and settles afterwards. A handler that spends something non-refundable <em>before</em> it can know whether it will answer
 * 2xx (typically an LLM call) has a hole: a request that ends non-2xx would normally release the
 * nonce claim, so the very same authorization could be replayed for another free run. Call {@link
 * #markWorkDone} right after such work; if the handler then answers non-2xx, the settlement filter
 * keeps the claim (outcome {@code not_charged_work_done}) so the authorization cannot be reused.
 * Nothing is settled in that case either. Without the mark, behaviour is unchanged. (An {@code
 * upfront} handler is settled before it runs and never releases its claim, so the mark changes
 * nothing there; see {@link #settled(HttpServletRequest)}.)
 *
 * <p>Every helper is a no-op / returns {@code null} for a request that is not a verified paid
 * request.
 */
public final class X402PaymentContext {

    private X402PaymentContext() {}

    /**
     * The payer's wallet address (the EIP-712 signer recovered from the signature and checked
     * against the authorization's {@code from}), or {@code null} if this request has not passed
     * payment verification. Safe to use as a rate-limit or accounting key.
     */
    public static @Nullable String payer(HttpServletRequest request) {
        X402PaymentAttempt attempt = attempt(request);
        return attempt != null && attempt.verified() ? attempt.payer() : null;
    }

    /**
     * The verified authorization's {@code validBefore} (EIP-3009, seconds since the epoch), or
     * {@code null} if this request has not passed payment verification. After this instant the
     * payment can no longer be settled, so a handler that runs before settlement should finish
     * well ahead of it (leaving room for the {@code /settle} call itself).
     */
    public static @Nullable Instant validBefore(HttpServletRequest request) {
        X402PaymentAttempt attempt = attempt(request);
        if (attempt == null || !attempt.verified()) {
            return null;
        }
        // Parsed and range-checked by RequiresPaymentInterceptor before markVerified.
        return Instant.ofEpochSecond(
                Long.parseLong(attempt.payload().payload().authorization().validBefore()));
    }

    /**
     * Whether this request's payment has already been settled successfully -- true only inside an
     * {@link io.github.orhanyarkin.x402.core.PaymentFlow#UPFRONT upfront} handler, which runs only
     * after its settlement succeeded. In the default flow the payment is settled after the handler,
     * so this is {@code false} while the handler runs. A handler can use it to skip protections
     * that only make sense for unpaid work (e.g. a budget for work done before settlement).
     */
    public static boolean settled(HttpServletRequest request) {
        X402PaymentAttempt attempt = attempt(request);
        return attempt != null && attempt.verified() && attempt.settled();
    }

    /**
     * The settlement transaction hash ({@code 0x} + 64 hex characters, validated) once this
     * request's payment is settled, else {@code null}; see {@link #settled(HttpServletRequest)}.
     */
    public static @Nullable String transactionHash(HttpServletRequest request) {
        X402PaymentAttempt attempt = attempt(request);
        return attempt != null && attempt.verified() && attempt.settled() ? attempt.txHash() : null;
    }

    /** Records that this request's handler already consumed non-refundable resources. */
    public static void markWorkDone(HttpServletRequest request) {
        X402PaymentAttempt attempt = attempt(request);
        if (attempt != null) {
            attempt.markWorkDone();
        }
    }

    private static @Nullable X402PaymentAttempt attempt(HttpServletRequest request) {
        Object attribute = request.getAttribute(X402SettlementFilter.ATTEMPT_ATTRIBUTE);
        return attribute instanceof X402PaymentAttempt attempt ? attempt : null;
    }
}
