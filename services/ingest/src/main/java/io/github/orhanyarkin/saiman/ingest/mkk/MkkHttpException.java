package io.github.orhanyarkin.saiman.ingest.mkk;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** The API answered with an error status. Only the status and a parsed {@code Retry-After} are kept. */
public class MkkHttpException extends MkkException {

    private static final long serialVersionUID = 1L;

    private final int status;

    private final long retryAfterMillis;

    public MkkHttpException(int status, @Nullable Duration retryAfter) {
        super("MKK request failed with status " + status);
        this.status = status;
        this.retryAfterMillis = retryAfter == null ? -1 : retryAfter.toMillis();
    }

    public int status() {
        return status;
    }

    /** The server's {@code Retry-After}, or null if it sent none. */
    public @Nullable Duration retryAfter() {
        return retryAfterMillis < 0 ? null : Duration.ofMillis(retryAfterMillis);
    }

    /** 429 and 5xx are worth another attempt; every other 4xx is a caller error and is not. */
    public boolean retryable() {
        return status == 429 || status >= 500;
    }
}
