package io.github.orhanyarkin.x402.client;

import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Validates {@code x402.client.allowed-plaintext-hosts}: the host names, besides loopback, that
 * {@link X402PaymentInterceptor} may send a {@code PAYMENT-SIGNATURE} to over plain {@code http}
 * (typically a container name on a private compose network, e.g. {@code seller-api}).
 *
 * <p>Entries are <b>exact host names</b>, compared case-insensitively against {@link
 * java.net.URI#getHost()}: no wildcard ({@code *}), no suffix or leading-dot pattern, no path, no
 * port, no userinfo, no CIDR range. An entry must be a plain DNS name made of letters, digits,
 * hyphens and dots. The list is empty by default, and a non-empty list is refused on any network
 * other than the Base Sepolia testnet: a plaintext signature is a bearer instrument until {@code
 * validBefore}, so this exception exists only for a testnet demo stack.
 */
public final class PlaintextHostAllowlist {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final int MAX_HOST_LENGTH = 253;

    private PlaintextHostAllowlist() {}

    /**
     * Validates {@code hosts} for {@code network} and returns them lowercased.
     *
     * @param hosts the configured entries; {@code null} is treated as empty
     * @param network the CAIP-2 network the client pays on
     * @return an immutable, lowercased copy of {@code hosts}
     * @throws IllegalStateException if the list is non-empty while {@code network} is not {@link
     *     TestnetAssets#NETWORK}, or any entry is not an exact host name; the message never echoes
     *     the rejected entry
     */
    public static List<String> requireValidAndNormalize(@Nullable List<String> hosts, String network) {
        Objects.requireNonNull(network, "network must not be null");
        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }
        if (!TestnetAssets.NETWORK.equals(network)) {
            throw new IllegalStateException("x402.client.allowed-plaintext-hosts must be empty unless the client pays"
                    + " on the Base Sepolia testnet (" + TestnetAssets.NETWORK + ")");
        }
        return hosts.stream().map(PlaintextHostAllowlist::requireExactHost).toList();
    }

    /** {@code true} if {@code host} (from {@link java.net.URI#getHost()}) is exactly one of {@code allowed}. */
    static boolean contains(List<String> allowed, String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowed.contains(normalized);
    }

    private static String requireExactHost(@Nullable String entry) {
        if (entry == null) {
            throw invalid();
        }
        String host = entry.strip().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH || !host.equals(entry.toLowerCase(Locale.ROOT))) {
            throw invalid();
        }
        // Checked explicitly first so the rule reads in code, not only inside the label pattern.
        if (host.contains("*") || host.contains("/") || host.contains(":") || host.contains("@")) {
            throw invalid();
        }
        for (String label : host.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                // Also rejects a leading or trailing dot (".seller-api", "seller-api.") and "a..b".
                throw invalid();
            }
        }
        return host;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("x402.client.allowed-plaintext-hosts entries must each be an exact host name"
                + " (letters, digits, hyphens and dots; no wildcard, port, path, userinfo or range)");
    }
}
