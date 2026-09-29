package io.github.orhanyarkin.x402.server;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-side x402 configuration ({@code x402.server.*}).
 *
 * <p>{@link #payTo()} is a public wallet address, not a secret: unlike {@code x402.client.*} (see
 * the client package), this record does not need a {@code toString()} override. It is only
 * required when at least one {@code @RequiresPayment} handler is registered; see {@link
 * RequiresPaymentRegistry}, which enforces that condition without Bean Validation so the rejected
 * value is never echoed in a bind-failure report.
 *
 * @param payTo the seller wallet address that receives payments, {@code 0x} + 40 hex characters;
 *     required iff a {@code @RequiresPayment} handler exists
 * @param facilitator the facilitator HTTP client configuration
 * @param maxTimeoutSeconds the maximum authorization window this starter will offer or accept, in
 *     seconds; must be in {@code (0, 300]}
 * @param publicBaseUrl the {@code https} origin this server is publicly reachable at (e.g. {@code
 *     https://api.example.com}), used to build the {@code resource.url} sent to the facilitator
 *     and echoed in {@code PAYMENT-REQUIRED}; when unset, only the request path is used. Never
 *     derived from the {@code Host} header or {@code X-Forwarded-*}, which are client-controlled
 *     and would let a request choose what this server tells the facilitator about itself.
 */
@ConfigurationProperties("x402.server")
public record X402ServerProperties(
        @Nullable String payTo,
        Facilitator facilitator,
        @DefaultValue("60") int maxTimeoutSeconds,
        @Nullable String publicBaseUrl) {

    /** Default facilitator base URL: the free, public x402 Foundation facilitator. */
    public static final String DEFAULT_FACILITATOR_URL = "https://x402.org/facilitator";

    private static final int MAX_ALLOWED_TIMEOUT_SECONDS = 300;

    public X402ServerProperties {
        if (facilitator == null) {
            facilitator = new Facilitator(
                    DEFAULT_FACILITATOR_URL, Facilitator.DEFAULT_CONNECT_TIMEOUT, Facilitator.DEFAULT_READ_TIMEOUT);
        }
        if (maxTimeoutSeconds <= 0 || maxTimeoutSeconds > MAX_ALLOWED_TIMEOUT_SECONDS) {
            // No echo: an operator can read their own configured value; a bind-failure report or
            // log line should not repeat it back verbatim as a matter of course.
            throw new IllegalStateException("x402.server.max-timeout-seconds must be between 1 and "
                    + MAX_ALLOWED_TIMEOUT_SECONDS + " seconds");
        }
        if (publicBaseUrl != null && !publicBaseUrl.isBlank() && !publicBaseUrl.startsWith("https://")) {
            throw new IllegalStateException("x402.server.public-base-url must be an https URL");
        }
    }

    /**
     * The facilitator HTTP client configuration; every field defaults when absent, so all three
     * constructor parameters may be {@code null} on input even though the accessors below never
     * return {@code null} (Spring Boot's relaxed constructor binding calls this constructor
     * reflectively, bypassing static null-checking, so this is safe even though the record
     * components below are intentionally not annotated {@code @Nullable}: doing so would also mark
     * the accessors nullable, which they are not after this compact constructor runs).
     *
     * @param url facilitator base URL; defaults to {@link #DEFAULT_FACILITATOR_URL}
     * @param connectTimeout TCP connect timeout; defaults to 10 seconds
     * @param readTimeout HTTP response read timeout; defaults to 15 seconds
     */
    public record Facilitator(String url, Duration connectTimeout, Duration readTimeout) {

        private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
        private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);

        public Facilitator {
            if (url == null || url.isBlank()) {
                url = DEFAULT_FACILITATOR_URL;
            }
            if (connectTimeout == null) {
                connectTimeout = DEFAULT_CONNECT_TIMEOUT;
            }
            if (readTimeout == null) {
                readTimeout = DEFAULT_READ_TIMEOUT;
            }
        }
    }
}
