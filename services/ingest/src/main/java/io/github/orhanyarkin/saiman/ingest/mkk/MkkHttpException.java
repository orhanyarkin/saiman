package io.github.orhanyarkin.saiman.ingest.mkk;

import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The API answered with an error status. Only the status, the MKK error code ({@code ER\d{3}},
 * parsed from the JSON error body; the body's message text is never kept) and a parsed {@code
 * Retry-After} survive.
 */
public class MkkHttpException extends MkkException {

    private static final long serialVersionUID = 1L;

    /** Unauthorized, sender IP, token invalid/expired, client IP null: a credential or network problem. */
    private static final Set<String> CREDENTIAL_CODES = Set.of("ER001", "ER002", "ER003", "ER004", "ER006", "ER007");

    /** "Disclosure not found" (the customised service uses ER008). */
    private static final Set<String> NOT_FOUND_CODES = Set.of("ER005", "ER008");

    private final int status;

    private final @Nullable String errorCode;

    private final long retryAfterMillis;

    public MkkHttpException(int status, @Nullable Duration retryAfter) {
        this(status, null, retryAfter);
    }

    public MkkHttpException(int status, @Nullable String errorCode, @Nullable Duration retryAfter) {
        this(status, errorCode, retryAfter, describe(status, errorCode));
    }

    protected MkkHttpException(int status, @Nullable String errorCode, @Nullable Duration retryAfter, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfterMillis = retryAfter == null ? -1 : retryAfter.toMillis();
    }

    private static String describe(int status, @Nullable String errorCode) {
        return "MKK request failed with status " + status + (errorCode == null ? "" : " (" + errorCode + ")");
    }

    public int status() {
        return status;
    }

    /** The MKK error code such as {@code ER005}, or null if the body had none. */
    public @Nullable String errorCode() {
        return errorCode;
    }

    /** The server's {@code Retry-After}, or null if it sent none. */
    public @Nullable Duration retryAfter() {
        return retryAfterMillis < 0 ? null : Duration.ofMillis(retryAfterMillis);
    }

    /** 429 and 5xx are worth another attempt; every other 4xx is a caller error and is not. */
    public boolean retryable() {
        return status == 429 || status >= 500;
    }

    /** ER005/ER008: the requested disclosure (or listing) does not exist. */
    public boolean notFoundCode() {
        return errorCode != null && NOT_FOUND_CODES.contains(errorCode);
    }

    /**
     * On {@code /disclosures} this is how MKK says "no matches from this index": an empty page,
     * not a failure.
     */
    public boolean emptyListing() {
        return status == 400 && notFoundCode();
    }

    /** Unauthorized or misconfigured: retrying cannot help and the run must stop. */
    public boolean credentialProblem() {
        return status == 401 || status == 403 || (errorCode != null && CREDENTIAL_CODES.contains(errorCode));
    }
}
