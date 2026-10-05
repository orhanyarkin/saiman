package io.github.orhanyarkin.saiman.evals.golden;

/** The golden set file is missing, unreadable or fails validation; the message lists every problem found. */
public class GoldenSetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GoldenSetException(String message) {
        super(message);
    }
}
