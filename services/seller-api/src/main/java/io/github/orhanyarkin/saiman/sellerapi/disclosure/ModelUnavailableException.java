package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/**
 * The model router refused or failed (daily cap, data-class policy, Valkey down, missing key, provider
 * error). One type for all of them on purpose: the response is a fixed 503 and the cause is only
 * logged by class name. No message, no cause, no stack trace.
 */
final class ModelUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ModelUnavailableException() {
        super("model unavailable", null, false, false);
    }
}
