package io.github.orhanyarkin.saiman.ledger.payment;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A fact disagrees with what the ledger already knows about the same authorization (amount, payee, asset or
 * expiry), or names an authorization ({@code payer, nonce}) the ledger already books under another payment key.
 * EIP-3009 signs all of them together with the nonce, so two different values for one authorization mean a producer
 * bug or a forged record: the event is quarantined (dead-letter topic, plus a {@code CONFLICTING_FACT} mismatch row)
 * for a human instead of being booked.
 */
public class ConflictingFactException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final @Nullable UUID paymentId;

    public ConflictingFactException(String message) {
        this(message, null);
    }

    public ConflictingFactException(String message, @Nullable UUID paymentId) {
        super(message);
        this.paymentId = paymentId;
    }

    /** The recorded payment the fact contradicts, if known. */
    public @Nullable UUID paymentId() {
        return paymentId;
    }
}
