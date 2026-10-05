package io.github.orhanyarkin.saiman.orchestrator.dashboard;

/** Too many dashboard reads are already in flight; the HTTP layer maps it to a fixed 503. */
public class ReadSaturatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReadSaturatedException() {
        super("dashboard reads are saturated");
    }
}
