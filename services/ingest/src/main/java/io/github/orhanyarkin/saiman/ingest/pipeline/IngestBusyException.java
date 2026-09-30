package io.github.orhanyarkin.saiman.ingest.pipeline;

/** An ingest run holds the advisory lock. Maps to 409 on the admin endpoint. */
public class IngestBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IngestBusyException() {
        super("an ingest run is in progress");
    }
}
