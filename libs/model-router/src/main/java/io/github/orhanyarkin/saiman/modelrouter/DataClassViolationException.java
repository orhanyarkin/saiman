package io.github.orhanyarkin.saiman.modelrouter;

/** A route was asked to handle a data class its provider is not allowed to see (ADR-0003). */
public class DataClassViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataClassViolationException(String message) {
        super(message);
    }
}
