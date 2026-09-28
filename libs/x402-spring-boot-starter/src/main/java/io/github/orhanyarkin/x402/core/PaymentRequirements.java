package io.github.orhanyarkin.x402.core;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One acceptable way to pay for a resource.
 *
 * <p>Mirrors the x402 v2 {@code PaymentRequirements} schema (specs/x402-specification-v2.md,
 * section 5.1.2). This starter only ever produces or accepts the {@code exact} scheme on {@link
 * TestnetAssets#NETWORK}; see {@link TestnetAssets#requireSupported(PaymentRequirements)}.
 *
 * <p>{@link #amount()} is the wire representation: a decimal string in atomic units, never a
 * {@code double}/{@code float}/{@code BigDecimal}. Use {@link AssetAmount#parse(String)} to
 * convert it to a {@code long}.
 *
 * <p>{@link #extra()} is {@code Map<String, Object>}, not {@code Map<String, String>}: the spec
 * allows any JSON object there, and nested values (numbers, booleans, objects, arrays) must
 * round-trip verbatim through {@link X402Codec} even though this starter only reads a few known
 * string-valued keys itself (see {@link #extraString(String)}).
 *
 * @param scheme payment scheme identifier, e.g. {@code "exact"}
 * @param network blockchain network identifier in CAIP-2 format, e.g. {@code "eip155:84532"}
 * @param amount required payment amount, as a decimal string of atomic token units
 * @param asset token contract address (or ISO 4217 code for fiat, unsupported by this starter)
 * @param payTo recipient wallet address
 * @param maxTimeoutSeconds maximum time allowed for payment completion
 * @param extra scheme-specific additional data; for {@code exact} on EVM this carries the EIP-712
 *     domain {@code name}/{@code version} and, optionally, {@code assetTransferMethod}/{@code
 *     paymentFlow}
 */
public record PaymentRequirements(
        String scheme,
        String network,
        String amount,
        String asset,
        String payTo,
        int maxTimeoutSeconds,
        @Nullable Map<String, Object> extra) {

    public PaymentRequirements {
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(network, "network must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(asset, "asset must not be null");
        Objects.requireNonNull(payTo, "payTo must not be null");
    }

    /**
     * Reads a string-valued key from {@link #extra()}, or {@code null} if absent or not a string.
     */
    public @Nullable String extraString(String key) {
        if (extra == null) {
            return null;
        }
        Object value = extra.get(key);
        return value instanceof String stringValue ? stringValue : null;
    }
}
