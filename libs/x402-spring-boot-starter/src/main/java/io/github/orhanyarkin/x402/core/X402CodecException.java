package io.github.orhanyarkin.x402.core;

/**
 * A {@code PAYMENT-REQUIRED}, {@code PAYMENT-SIGNATURE} or {@code PAYMENT-RESPONSE} header could
 * not be encoded or decoded.
 *
 * <p>{@link #getMessage()} never contains the raw header value or any decoded payload content
 * (ADR-0006 amendment), and it never has a cause: Jackson's own messages quote input. Property
 * names from the input appear only when they are short identifiers. It is safe to log or return
 * in a Problem Details response body.
 */
public final class X402CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public X402CodecException(String message) {
        super(message);
    }
}
