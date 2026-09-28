package io.github.orhanyarkin.x402.core;

import java.util.regex.Pattern;

/**
 * A payment amount in atomic token units, e.g. 1 USDC (6 decimals) is {@code 1_000_000}.
 *
 * <p>x402 carries amounts on the wire as decimal strings (never JSON numbers, to avoid precision
 * loss); this type is the one place that string is parsed into a {@code long}. No {@code
 * double}/{@code float}/{@code BigDecimal} appears anywhere in this starter's public API (rule 4
 * in {@code CLAUDE.md}: money is integers).
 *
 * @param atomicUnits the amount in atomic units; never negative
 */
public record AssetAmount(long atomicUnits) {

    // A canonical non-negative decimal integer: "0", or a digit 1-9 followed by digits.
    // No sign, no decimal point, no whitespace, no leading zeros.
    private static final Pattern WIRE_PATTERN = Pattern.compile("0|[1-9][0-9]*");

    public AssetAmount {
        if (atomicUnits < 0) {
            throw new IllegalArgumentException("AssetAmount atomic units must not be negative: " + atomicUnits);
        }
    }

    /**
     * Parses a wire-format decimal string into an {@link AssetAmount}.
     *
     * @param wireValue the {@code amount}/{@code value} field from a wire object
     * @return the parsed amount
     * @throws IllegalArgumentException if {@code wireValue} is null, empty, negative, contains a
     *     decimal point or non-digit characters, has a leading zero, or overflows a signed 64-bit
     *     integer; the message never echoes {@code wireValue} itself (it is untrusted wire input
     *     and could otherwise carry, for example, a CR/LF sequence into a log line)
     */
    public static AssetAmount parse(String wireValue) {
        if (wireValue == null || wireValue.isEmpty()) {
            throw new IllegalArgumentException("amount must not be null or empty");
        }
        if (!WIRE_PATTERN.matcher(wireValue).matches()) {
            throw new IllegalArgumentException(
                    "amount must be a non-negative decimal integer with no leading zeros, sign or fractional part");
        }
        try {
            return new AssetAmount(Long.parseLong(wireValue));
        } catch (NumberFormatException e) {
            // Deliberately no cause and no echoed value: NumberFormatException's own message
            // includes the input string.
            throw new IllegalArgumentException("amount overflows a 64-bit atomic unit value");
        }
    }

    /** Renders this amount as the wire-format decimal string. */
    public String toWireValue() {
        return Long.toString(atomicUnits);
    }
}
