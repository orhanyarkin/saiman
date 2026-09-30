package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.util.UUID;

/**
 * A signed authorization left the process and the outcome is unknown (5xx, second 402, redirect,
 * timeout, or no valid settlement header). The intent is HELD: its amount keeps counting against
 * the run budget and the daily cap until M4 reconciles it on chain. Never retry under a new key.
 */
public final class PaymentOutcomeUnknownException extends PaidCallException {

    private static final long serialVersionUID = 1L;

    public PaymentOutcomeUnknownException(UUID paymentIntentId) {
        super(paymentIntentId, "payment outcome unknown; the reservation is held");
    }
}
