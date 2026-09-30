package io.github.orhanyarkin.saiman.ingest.mkk;

/** An MKK call failed. Messages carry status codes only: never headers, URLs with secrets or bodies. */
public class MkkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MkkException(String message) {
        super(message);
    }
}
