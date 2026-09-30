package io.github.orhanyarkin.saiman.ingest.mkk;

/** The circuit breaker is open: MKK has been failing. The pipeline stops the run instead of burning attempts. */
public class MkkCircuitOpenException extends MkkException {

    private static final long serialVersionUID = 1L;

    public MkkCircuitOpenException() {
        super("MKK circuit breaker is open");
    }
}
