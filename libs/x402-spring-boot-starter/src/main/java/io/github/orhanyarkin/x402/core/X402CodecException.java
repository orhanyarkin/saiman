package io.github.orhanyarkin.x402.core;

/**
 * A {@code PAYMENT-REQUIRED}, {@code PAYMENT-SIGNATURE} or {@code PAYMENT-RESPONSE} header could
 * not be encoded or decoded.
 *
 * <p>{@link #getMessage()} never contains the raw header value or any decoded payload content
 * (ADR-0006 amendment): it is safe to log or return in a Problem Details response body. The
 * {@linkplain #getCause() cause}, when present, is the underlying Jackson exception and MAY
 * reference input content in its own message; callers building client-facing or logged output
 * MUST use only {@link #getMessage()} of this exception, never the cause chain.
 */
public final class X402CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public X402CodecException(String message) {
        super(message);
    }

    public X402CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
