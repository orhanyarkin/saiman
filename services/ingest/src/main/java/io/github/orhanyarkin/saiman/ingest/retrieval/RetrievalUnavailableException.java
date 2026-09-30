package io.github.orhanyarkin.saiman.ingest.retrieval;

/** The query could not be embedded (router refusal, cap reached, provider down). Maps to 503. */
public class RetrievalUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetrievalUnavailableException(String causeClass) {
        super("query embedding unavailable: " + causeClass);
    }
}
