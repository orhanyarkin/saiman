package io.github.orhanyarkin.x402.server;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

/**
 * Lets a {@link RequiresPayment} handler talk back to the payment machinery for the current request.
 *
 * <p>The starter serves a resource only after the handler answers 2xx, and settles afterwards. A
 * handler that spends something non-refundable <em>before</em> it can know whether it will answer
 * 2xx (typically an LLM call) has a hole: a request that ends non-2xx would normally release the
 * nonce claim, so the very same authorization could be replayed for another free run. Call {@link
 * #markWorkDone} right after such work; if the handler then answers non-2xx, the settlement filter
 * keeps the claim (outcome {@code not_charged_work_done}) so the authorization cannot be reused.
 * Nothing is settled in that case either. Without the mark, behaviour is unchanged.
 *
 * <p>Both helpers are no-ops / return {@code null} for a request that is not a verified paid
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
