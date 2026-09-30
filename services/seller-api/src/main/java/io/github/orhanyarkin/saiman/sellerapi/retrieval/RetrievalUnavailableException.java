package io.github.orhanyarkin.saiman.sellerapi.retrieval;

/**
 * The ingest service could not answer (transport error, timeout, 4xx/5xx, open circuit or an
 * unreadable body). Deliberately carries no message and no cause: it is mapped to a fixed 503
 * Problem Details and must never echo the query, a URL or upstream content.
 */
public final class RetrievalUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetrievalUnavailableException() {
        super("retrieval unavailable", null, false, false);
    }
}
