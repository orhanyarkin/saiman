package io.github.orhanyarkin.x402.core;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The EIP-3009 {@code transferWithAuthorization} parameters signed by the payer.
 *
 * <p>Mirrors the {@code Authorization} object in the x402 v2 {@code exact}-on-EVM scheme
 * (specs/schemes/exact/scheme_exact_evm.md). Every field is a wire-format string, matching the
 * spec exactly: {@link #value()}, {@link #validAfter()} and {@link #validBefore()} are decimal
 * strings (see {@link AssetAmount}), and {@link #nonce()} is a {@code 0x}-prefixed 32-byte hex
 * string. Signing and recovery live in {@code io.github.orhanyarkin.x402.evm}, which builds the
 * EIP-712 digest for this struct against the fixed USDC domain in {@link TestnetAssets}.
 *
 * <p><b>Canonical form is enforced, not just well-formed-ness.</b> web3j's ABI encoder (and, more
 * generally, EIP-712/ABI encoding itself) accepts many textually different representations of the
 * same on-chain value: {@code "0x2710"}, {@code "010000"} and {@code "+10000"} all encode
 * identically to the canonical {@code "10000"}; hex fields accept mixed case, a missing {@code 0x}
 * prefix, or an odd length. Left unchecked, that lets two textually different {@code
 * PaymentPayload}s decode to the same on-chain authorization -- e.g. a resend with {@link
 * #nonce()} upper-cased could slip past a pre-settlement replay check keyed on the raw string,
 * even though the token contract's own EIP-3009 nonce (the final guard either way) would still
 * reject it. The compact constructor below therefore rejects anything that isn't already in
 * canonical form, so this type can never carry an ambiguous representation.
 *
 * @param from payer's wallet address; {@code 0x} + 40 hex characters
 * @param to recipient's wallet address; {@code 0x} + 40 hex characters
 * @param value payment amount, as a canonical decimal string of atomic units (uint256 range)
 * @param validAfter unix timestamp (canonical decimal string) after which the authorization
 *     becomes valid
 * @param validBefore unix timestamp (canonical decimal string) after which the authorization
 *     expires
 * @param nonce {@code 0x}-prefixed 32-byte (64 hex character) random nonce preventing replay
 */
public record Eip3009Authorization(
        String from, String to, String value, String validAfter, String validBefore, String nonce) {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern NONCE_PATTERN = Pattern.compile("0x[0-9a-fA-F]{64}");
    private static final Pattern CANONICAL_DECIMAL_PATTERN = Pattern.compile("0|[1-9][0-9]*");
    private static final BigInteger UINT256_MAX = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    public Eip3009Authorization {
        requireAddress(from, "from");
        requireAddress(to, "to");
        requireCanonicalUint256(value, "value");
        requireCanonicalUint256(validAfter, "validAfter");
        requireCanonicalUint256(validBefore, "validBefore");
        requireNonce(nonce);
    }

    /**
     * A case-normalised {@code (from, nonce)} key for replay-prevention stores: {@code
     * lower(from) + lower(nonce)}.
     *
     * <p>Both {@link #from()} and {@link #nonce()} may legitimately vary in case on the wire
     * (EIP-55 checksummed addresses use mixed case by design), but they identify the same
     * on-chain authorization regardless of case. A nonce-claim store keyed on the raw strings
     * would treat a re-cased resend as a different authorization; keying on this method's result
     * instead does not.
     */
    public String canonicalNonceKey() {
        return from.toLowerCase(Locale.ROOT) + nonce.toLowerCase(Locale.ROOT);
    }

    /**
     * Re-checks every field's canonical form.
     *
     * <p>A Java record's compact constructor cannot be bypassed, so any existing {@code
     * Eip3009Authorization} instance already satisfies this -- this method exists so
     * crypto-facing entry points in {@code io.github.orhanyarkin.x402.evm} (such as {@code
     * Eip3009TypedData.digest}) can state that dependency explicitly rather than relying
     * implicitly on that guarantee.
     */
    public static void validate(Eip3009Authorization authorization) {
        requireAddress(authorization.from, "from");
        requireAddress(authorization.to, "to");
        requireCanonicalUint256(authorization.value, "value");
        requireCanonicalUint256(authorization.validAfter, "validAfter");
        requireCanonicalUint256(authorization.validBefore, "validBefore");
        requireNonce(authorization.nonce);
    }

    private static void requireAddress(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!ADDRESS_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a 0x-prefixed 20-byte hex address");
        }
    }

    private static void requireNonce(String nonce) {
        Objects.requireNonNull(nonce, "nonce must not be null");
        if (!NONCE_PATTERN.matcher(nonce).matches()) {
            throw new IllegalArgumentException("nonce must be a 0x-prefixed 32-byte hex value");
        }
    }

    private static void requireCanonicalUint256(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!CANONICAL_DECIMAL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field
                    + " must be a non-negative decimal integer with no leading zeros, sign or"
                    + " fractional part");
        }
        if (new BigInteger(value).compareTo(UINT256_MAX) > 0) {
            throw new IllegalArgumentException(field + " overflows a uint256");
        }
    }
}
