package io.github.orhanyarkin.x402.client;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates and normalizes {@code x402.client.allowed-pay-to} entries: shared by {@link
 * PropertiesSpendGuard} and {@link X402PaymentInterceptor}, which each independently hold their
 * own copy of the configured allowlist (see {@code X402ClientAutoConfiguration}'s Javadoc on why).
 */
final class PayToAllowlist {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("0x[0-9a-fA-F]{40}");

    private PayToAllowlist() {}

    /**
     * @return {@code allowedPayTo}, each entry lowercased
     * @throws IllegalArgumentException if {@code allowedPayTo} is null or empty, or any entry is
     *     not a {@code 0x}-prefixed 20-byte hex address; the message never echoes the rejected
     *     entry — a malformed address here would otherwise either fail loudly this way, or (worse)
     *     silently match nothing at request time, which is a harder bug to notice than a startup
     *     failure
     */
    static List<String> requireValidAndNormalize(List<String> allowedPayTo) {
        if (allowedPayTo == null || allowedPayTo.isEmpty()) {
            throw new IllegalArgumentException("allowedPayTo must not be empty");
        }
        return allowedPayTo.stream().map(PayToAllowlist::requireValidAddress).toList();
    }

    private static String requireValidAddress(String address) {
        if (address == null || !ADDRESS_PATTERN.matcher(address).matches()) {
            throw new IllegalArgumentException("allowedPayTo entries must each be a 0x-prefixed 20-byte hex address");
        }
        return address.toLowerCase(Locale.ROOT);
    }
}
