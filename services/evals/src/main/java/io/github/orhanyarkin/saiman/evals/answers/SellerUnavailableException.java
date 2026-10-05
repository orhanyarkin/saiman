package io.github.orhanyarkin.saiman.evals.answers;

import org.jspecify.annotations.Nullable;

/**
 * The seller could not take the question and no further question should be tried: run guard limit (429), guard
 * undecidable (503 Problem Detail) or a persistent transport failure. Not a question outcome.
 */
public class SellerUnavailableException extends RuntimeException {

    private final int status;

    public SellerUnavailableException(String message, int status, @Nullable Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** HTTP status, or 0 for a transport failure. */
    public int status() {
        return status;
    }
}
