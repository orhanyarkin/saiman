package io.github.orhanyarkin.saiman.evals.ingest;

/** Ingest could not answer a retrieval request (after retries) or answered with something unusable. */
public class IngestUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IngestUnavailableException(Throwable cause) {
        super("ingest retrieval failed: " + cause.getClass().getSimpleName(), cause);
    }
}
