package io.github.orhanyarkin.saiman.evmrpc;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Base Sepolia JSON-RPC client ({@code saiman.chain.*}).
 *
 * <p>The URL is validated on construction so a bad value fails startup before any request is made: https only
 * (plain http only for a loopback host, which is how tests reach a local stub), host on the exact allowlist, no
 * user info, no query or fragment (a key there would leak into logs and traces), no IP literal (loopback
 * excepted). There is deliberately no property that disables these checks.
 *
 * @param rpcUrl the JSON-RPC endpoint, e.g. {@code https://sepolia.base.org}
 * @param allowedHosts exact host names the URL may point to
 * @param connectTimeout TCP connect timeout
 * @param readTimeout response timeout
 * @param maxRequestsPerSecond client-side rate limit (the public RPC is rate-limited)
 * @param logChunkBlocks block span of one {@code eth_getLogs} request (at most 1000)
 * @param retryAttempts attempts per call, including the first (retried only on IO errors, 429 and 5xx)
 * @param retryWait base wait between attempts (exponential, jittered)
 * @param maxLogRangeBlocks widest block range one {@code findAuthorizationTx} call may scan
 */
@ConfigurationProperties("saiman.chain")
public record ChainProperties(
        String rpcUrl,
        @DefaultValue("sepolia.base.org") List<String> allowedHosts,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("5") int maxRequestsPerSecond,
        @DefaultValue("1000") int logChunkBlocks,
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("300ms") Duration retryWait,
        @DefaultValue("100000") long maxLogRangeBlocks) {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    public ChainProperties {
        if (rpcUrl == null || rpcUrl.isBlank()) {
            throw new IllegalArgumentException("saiman.chain.rpc-url must be set");
        }
        allowedHosts =
                allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        checkUrl(rpcUrl, allowedHosts);
        if (connectTimeout.isNegative()
                || connectTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.isZero()) {
            throw new IllegalArgumentException("saiman.chain timeouts must be positive");
        }
        if (maxRequestsPerSecond <= 0) {
            throw new IllegalArgumentException("saiman.chain.max-requests-per-second must be positive");
        }
        if (logChunkBlocks < 1 || logChunkBlocks > 1000) {
            throw new IllegalArgumentException("saiman.chain.log-chunk-blocks must be between 1 and 1000");
        }
        if (retryAttempts < 1) {
            throw new IllegalArgumentException("saiman.chain.retry-attempts must be at least 1");
        }
        if (retryWait.toMillis() < 1) {
            throw new IllegalArgumentException("saiman.chain.retry-wait must be at least 1ms");
        }
        if (maxLogRangeBlocks < 1) {
            throw new IllegalArgumentException("saiman.chain.max-log-range-blocks must be positive");
        }
    }

    /** Defaults for everything but the URL. */
    public static ChainProperties of(String rpcUrl) {
        return new ChainProperties(
                rpcUrl,
                List.of("sepolia.base.org"),
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                5,
                1000,
                3,
                Duration.ofMillis(300),
                100_000);
    }

    /** True when the host of the validated URL is a loopback name or address. */
    static boolean isLoopback(String host) {
        return LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
    }

    private static void checkUrl(String rpcUrl, List<String> allowedHosts) {
        URI uri;
        try {
            uri = URI.create(rpcUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("saiman.chain.rpc-url is not a valid URI");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("saiman.chain.rpc-url must not carry user info");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("saiman.chain.rpc-url must not carry a query or fragment");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("saiman.chain.rpc-url must have a host");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (isLoopback(host)) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("saiman.chain.rpc-url must use http(s)");
            }
            return;
        }
        if (!scheme.equals("https")) {
            throw new IllegalArgumentException("saiman.chain.rpc-url must use https");
        }
        if (host.startsWith("[") || host.matches("[0-9.]+")) {
            throw new IllegalArgumentException("saiman.chain.rpc-url must use a host name, not an IP address");
        }
        if (!allowedHosts.contains(host)) {
            throw new IllegalArgumentException("saiman.chain.rpc-url host is not on saiman.chain.allowed-hosts");
        }
    }
}
