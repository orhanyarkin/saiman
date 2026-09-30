package io.github.orhanyarkin.saiman.ingest.mkk;

/** A transport failure (timeout, connection reset). Retryable; the cause is deliberately not chained. */
public class MkkIoException extends MkkException {

    private static final long serialVersionUID = 1L;

    public MkkIoException(String causeClass) {
        super("MKK I/O error: " + causeClass);
    }
}
