package io.github.orhanyarkin.saiman.ledger.payment;

/**
 * A {@code payments.*} record that is not a valid event of its topic (bad JSON, unknown or missing field, failed
 * validation). Not retryable: it goes straight to the dead-letter topic. The message never echoes the payload.
 */
public class MalformedPaymentEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MalformedPaymentEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedPaymentEventException(String message) {
        super(message);
    }
}
