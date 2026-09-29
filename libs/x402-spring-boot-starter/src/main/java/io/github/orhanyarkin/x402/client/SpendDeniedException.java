package io.github.orhanyarkin.x402.client;

/**
 * {@link SpendGuard#reserve(PaymentIntent)} refused a payment: over budget, payee not allowed, or
 * a reused idempotency key. Thrown before any signing happens, so a denied payment never produces
 * a signature.
 */
public final class SpendDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SpendDeniedException(String message) {
        super(message);
    }
}
