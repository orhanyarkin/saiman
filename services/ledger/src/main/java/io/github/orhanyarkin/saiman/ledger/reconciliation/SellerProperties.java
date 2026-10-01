package io.github.orhanyarkin.saiman.ledger.reconciliation;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.ledger.seller.*}: where reconciliation asks seller-api to corroborate credit notes (ADR-0021).
 *
 * <p>Validated on construction, in the style of {@code saiman.chain.*}: the host must be exactly on {@code
 * allowed-hosts} (compose: {@code seller-api}) or loopback (tests), no user info, query, fragment or path, no IP
 * literal other than loopback. Plain {@code http} is accepted because the target is the compose-internal seller;
 * {@code scripts/check-compose-policy.sh} pins both values. There is no property that disables these checks.
 *
 * @param baseUrl e.g. {@code http://seller-api:8081}
 * @param allowedHosts exact host names the URL may point to
 * @param connectTimeout TCP connect timeout
 * @param readTimeout response timeout
 * @param retryAttempts attempts per lookup, including the first (retried only on IO errors, 429 and 5xx)
 * @param retryWait base wait between attempts (exponential, jittered)
 * @param circuitOpenWait how long the breaker stays open after the seller kept failing
 */
@ConfigurationProperties("saiman.ledger.seller")
public record SellerProperties(
        @DefaultValue("http://seller-api:8081") String baseUrl,
        @DefaultValue("seller-api") List<String> allowedHosts,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("200ms") Duration retryWait,
        @DefaultValue("30s") Duration circuitOpenWait) {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    public SellerProperties {
        allowedHosts = allowedHosts.stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .toList();
        baseUrl = checkUrl(baseUrl, allowedHosts);
        if (connectTimeout.isNegative()
                || connectTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.isZero()
                || circuitOpenWait.isNegative()
                || circuitOpenWait.isZero()) {
            throw new IllegalArgumentException("saiman.ledger.seller timeouts must be positive");
        }
        if (retryAttempts < 1 || retryAttempts > 5) {
            throw new IllegalArgumentException("saiman.ledger.seller.retry-attempts must be between 1 and 5");
        }
        if (retryWait.toMillis() < 1) {
            throw new IllegalArgumentException("saiman.ledger.seller.retry-wait must be at least 1ms");
        }
    }

    /** Defaults for everything but the URL and its host (tests). */
    public static SellerProperties of(String baseUrl) {
        return new SellerProperties(
                baseUrl,
                List.of("seller-api"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                3,
                Duration.ofMillis(200),
                Duration.ofSeconds(30));
    }

    /** The validated base URL without a trailing slash. */
    private static String checkUrl(String baseUrl, List<String> allowedHosts) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("saiman.ledger.seller.base-url is not a valid URI");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("saiman.ledger.seller.base-url must not carry user info");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("saiman.ledger.seller.base-url must not carry a query or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("saiman.ledger.seller.base-url must not carry a path");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("saiman.ledger.seller.base-url must use http(s)");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("saiman.ledger.seller.base-url must have a host");
        }
        if (!LOOPBACK.contains(host)) {
            if (host.startsWith("[") || host.matches("[0-9.]+")) {
                throw new IllegalArgumentException(
                        "saiman.ledger.seller.base-url must use a host name, not an IP address");
            }
            if (!allowedHosts.contains(host)) {
                throw new IllegalArgumentException(
                        "saiman.ledger.seller.base-url host is not on saiman.ledger.seller.allowed-hosts");
            }
        }
        return path.isEmpty() ? baseUrl : baseUrl.substring(0, baseUrl.length() - 1);
    }
}
