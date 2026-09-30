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
 * @param readTimeout read timeout; above the seller's own handler deadline, so a slow answer is not
 *     cut off while the seller still settles (a cut-off paid call becomes a held reservation)
 */
@ConfigurationProperties("saiman.orchestrator.seller")
public record SellerProperties(
        @DefaultValue("http://seller-api:8081") URI baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("35s") Duration readTimeout) {

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
    }
}
