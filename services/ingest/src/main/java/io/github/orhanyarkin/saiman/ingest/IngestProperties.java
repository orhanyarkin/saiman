package io.github.orhanyarkin.saiman.ingest;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.ingest.*}. Records with defaults, so a test overrides only what it needs.
 *
 * @param tickers BIST tickers to ingest; unknown ones are logged and skipped
 * @param classes KAP disclosure classes to keep (ODA material events, DG other; FR is skipped, ADR-0010)
 * @param maxAttempts a document failing this many times is parked in the DLQ
 * @param retrieval query-time settings
 */
@ConfigurationProperties("saiman.ingest")
public record IngestProperties(
        @DefaultValue Mkk mkk,
        @DefaultValue("THYAO") List<String> tickers,
        @DefaultValue({"ODA", "DG"}) Set<String> classes,
        @DefaultValue Chunk chunk,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue Backfill backfill,
        @DefaultValue Retrieval retrieval) {

    /**
     * MKK API client settings. The credential is the base64 Basic value; {@code toString} never prints it.
     *
     * @param credentials base64 {@code user:password} value from the {@code mkk_credentials} secret
     * @param ratePerMinute client-side rate limit (the portal allows 6, we stay at 5)
     * @param emptyPageStep how far the cursor jumps on an empty page of the windowed listing
     * @param retryAttempts total attempts per call for 429/5xx/IO errors
     * @param retryWait base wait between attempts (jittered, exponential); {@code Retry-After} wins if longer
     * @param maxRetryAfter upper bound on honouring a {@code Retry-After} header
     * @param rateLimiterTimeout how long a call may wait for a rate-limiter permit
     * @param circuitOpenWait how long the circuit breaker stays open before probing MKK again
     */
    public record Mkk(
            @DefaultValue("https://apigwdev.mkk.com.tr/api/vyk")
            String baseUrl,

            @DefaultValue("") String credentials,
            @DefaultValue("5") int ratePerMinute,
            @DefaultValue("5000") long emptyPageStep,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout,
            @DefaultValue("4") int retryAttempts,
            @DefaultValue("2s") Duration retryWait,
            @DefaultValue("2m") Duration maxRetryAfter,
            @DefaultValue("1h") Duration rateLimiterTimeout,
            @DefaultValue("30s") Duration circuitOpenWait) {

        @Override
        public String toString() {
            return "Mkk[baseUrl=" + baseUrl + ", credentials=<redacted>]";
        }
    }

    /**
     * @param targetTokens approximate chunk size for the token splitter
     */
    public record Chunk(@DefaultValue("400") int targetTokens) {}

    /**
     * @param recencyWeight multiplier of the recency leg's RRF term for "latest ..." questions (ADR-0025); 1.0 is
     *     plain RRF, 0 switches the leg's influence off
     */
    public record Retrieval(@DefaultValue("3.0") double recencyWeight) {}

    /**
     * @param enabled start the backfill in the background at startup
     */
    public record Backfill(@DefaultValue("false") boolean enabled) {}
}
