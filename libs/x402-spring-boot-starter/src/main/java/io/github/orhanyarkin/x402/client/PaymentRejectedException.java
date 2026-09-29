package io.github.orhanyarkin.x402.client;

/**
 * {@link X402PaymentInterceptor} refused to attempt a payment: the outgoing request had no {@code
 * Idempotency-Key} header, or none of the server's {@code accepts} offers is one this starter can
 * and will pay (unsupported scheme/network/asset, a {@code payTo} outside the configured
 * allowlist, or an amount above the configured per-request maximum).
 *
 * <p>Never echoes the rejected {@code payTo}, amount or network: the server that produced the
 * offer is untrusted input, and any of those values could otherwise resurface verbatim in an
 * application's logs or error responses (ADR-0006 amendment).
 */
public final class PaymentRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PaymentRejectedException(String message) {
        super(message);
    }
}
