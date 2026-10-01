package io.github.orhanyarkin.saiman.ledger.payment;

/**
 * A fact disagrees with what the ledger already knows about the same authorization (amount, payee, asset or
 * expiry). EIP-3009 signs all of them together with the nonce, so two different values for one payment key mean
 * a producer bug: the event is quarantined (dead-letter topic) for a human instead of being booked.
 */
public class ConflictingFactException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public ConflictingFactException(String message) {
        super(message);
    }
}
