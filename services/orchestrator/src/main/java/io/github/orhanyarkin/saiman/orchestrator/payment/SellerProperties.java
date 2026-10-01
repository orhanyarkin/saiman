package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.orchestrator.seller.*}: where the paid tools live. The base URL is configuration
 * only (standing rule 5: model text never becomes a URL).
 *
 * @param baseUrl scheme, host and optional port of the seller-api, e.g. {@code
 *     http://seller-api:8081}; no path, query, fragment or user info
 * @param connectTimeout TCP connect timeout
 * @param readTimeout read timeout of the paying client, at least {@link #MIN_READ_TIMEOUT}: the
 *     seller buffers a paid answer until the facilitator's {@code /settle} returns, so a settled 200
 *     can arrive as late as the authorization's {@code validBefore}, which the starter sets at most
 *     60 s ahead ({@code X402PaymentInterceptor.MAX_VALIDITY_SECONDS}). A shorter timeout cuts off
 *     answers the seller has already settled (the paid call then becomes a held reservation)
 */
@ConfigurationProperties("saiman.orchestrator.seller")
public record SellerProperties(
        @DefaultValue("http://seller-api:8081") URI baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("65s") Duration readTimeout) {

    /**
     * The smallest read timeout startup accepts: the starter's 60 s authorization validity (the
     * latest a settled answer can arrive) plus a 5 s margin for the response to travel.
     */
    public static final Duration MIN_READ_TIMEOUT = Duration.ofSeconds(65);

    public SellerProperties {
        String scheme = baseUrl.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("saiman.orchestrator.seller.base-url must be an http(s) URL");
        }
        if (baseUrl.getHost() == null
                || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null
                || !(baseUrl.getRawPath() == null
                        || baseUrl.getRawPath().isEmpty()
                        || "/".equals(baseUrl.getRawPath()))) {
            throw new IllegalArgumentException(
                    "saiman.orchestrator.seller.base-url must be scheme://host[:port] with nothing else");
        }
        if (connectTimeout.isNegative()
                || connectTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.isZero()) {
            throw new IllegalArgumentException("saiman.orchestrator.seller timeouts must be positive");
        }
        if (readTimeout.compareTo(MIN_READ_TIMEOUT) < 0) {
            throw new IllegalArgumentException("saiman.orchestrator.seller.read-timeout must be at least "
                    + MIN_READ_TIMEOUT.toSeconds()
                    + "s: a settled answer can arrive up to the authorization's validBefore (at most 60 s ahead)");
        }
    }
}
