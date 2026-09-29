package io.github.orhanyarkin.saiman.shared.money;

import java.util.regex.Pattern;

/**
 * A non-negative amount of one asset, held as {@code long} atomic units (CLAUDE.md rule 4): USDC
 * uses 6 decimals, so {@code 10000} atomic units is 0.01 USDC; USD costs are tracked in micro-dollars
 * (6 decimals) the same way. There is no {@code double}/{@code float} anywhere, and formatting for
 * humans happens at the UI edge only.
 *
 * <p>Direction (debit/credit, spend/refund) is not part of the value: the ledger entry or budget
 * counter that holds a {@code Money} carries it.
 *
 * @param atomicUnits the amount in the asset's smallest unit, {@code >= 0}
 * @param asset asset code, upper-case letters and digits (e.g. {@code USDC}, {@code USD})
 * @param decimals decimal places of one whole unit of the asset ({@code 0..18})
 */
public record Money(long atomicUnits, String asset, int decimals) implements Comparable<Money> {

    /** Decimals used for USDC and for USD micro-dollar cost tracking. */
    public static final int SIX_DECIMALS = 6;

    private static final Pattern ASSET = Pattern.compile("[A-Z0-9]{2,10}");

    public Money {
        if (atomicUnits < 0) {
            throw new IllegalArgumentException("money must not be negative");
        }
        if (!ASSET.matcher(asset).matches()) {
            throw new IllegalArgumentException("asset must be 2-10 upper-case letters or digits");
        }
        if (decimals < 0 || decimals > 18) {
            throw new IllegalArgumentException("decimals must be between 0 and 18");
        }
    }

    /** USD cost in micro-dollars (1_000_000 = 1 USD). */
    public static Money usdMicros(long microDollars) {
        return new Money(microDollars, "USD", SIX_DECIMALS);
    }

    /** USDC in atomic units (1_000_000 = 1 USDC). */
    public static Money usdc(long atomicUnits) {
        return new Money(atomicUnits, "USDC", SIX_DECIMALS);
    }

    /** The zero amount of the same asset. */
    public Money zero() {
        return new Money(0, asset, decimals);
    }

    public boolean isZero() {
        return atomicUnits == 0;
    }

    /**
     * Adds two amounts of the same asset.
     *
     * @throws ArithmeticException on overflow
     * @throws IllegalArgumentException for a different asset
     */
    public Money plus(Money other) {
        requireSameAsset(other);
        return new Money(Math.addExact(atomicUnits, other.atomicUnits), asset, decimals);
    }

    /**
     * Subtracts an amount of the same asset.
     *
     * @throws IllegalArgumentException if the result would be negative or the asset differs
     */
    public Money minus(Money other) {
        requireSameAsset(other);
        return new Money(atomicUnits - other.atomicUnits, asset, decimals);
    }

    /**
     * Compares two amounts of the same asset.
     *
     * @throws IllegalArgumentException for a different asset
     */
    @Override
    public int compareTo(Money other) {
        requireSameAsset(other);
        return Long.compare(atomicUnits, other.atomicUnits);
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    private void requireSameAsset(Money other) {
        if (!asset.equals(other.asset) || decimals != other.decimals) {
            throw new IllegalArgumentException("money in different assets cannot be combined");
        }
    }
}
