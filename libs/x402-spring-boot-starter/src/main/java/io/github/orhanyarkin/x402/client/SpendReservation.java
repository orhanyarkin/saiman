package io.github.orhanyarkin.x402.client;

import java.util.Objects;

/**
 * An in-progress spend authorized by {@link SpendGuard#reserve(PaymentIntent)}, not yet committed
 * or released.
 *
 * @param idempotencyKey the reserved {@code Idempotency-Key}, echoed from {@link #intent()} for
 *     convenience
 * @param intent the {@link PaymentIntent} this reservation was created for
 */
public record SpendReservation(String idempotencyKey, PaymentIntent intent) {

    public SpendReservation {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(intent, "intent must not be null");
    }
}
