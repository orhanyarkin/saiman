package io.github.orhanyarkin.saiman.orchestrator.dashboard;

/** A dashboard read ran into its statement timeout; the HTTP layer maps it to a fixed 503. */
public class ReadTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReadTimeoutException(Throwable cause) {
        super("dashboard read timed out", cause);
    }
}
