package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/**
 * The model router refused or failed (daily cap, data-class policy, Redis down, missing key, provider
 * error). One type for all of them on purpose: the response is a fixed 503 and the cause is only
 * logged by class name. No message, no cause, no stack trace.
 *
 * <p>{@link #dailyCap()} tells the internal eval API (which reports {@code LLM_CAP}) that the router's
 * daily USD cap was the reason; the paid endpoints ignore it and answer the same 503 either way.
 */
final class ModelUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean dailyCap;

    ModelUnavailableException() {
        this(false);
    }

    ModelUnavailableException(boolean dailyCap) {
        super("model unavailable", null, false, false);
        this.dailyCap = dailyCap;
    }

    /** Whether the router refused because its daily USD cap is used up (nothing was sent). */
    boolean dailyCap() {
        return dailyCap;
    }
}
