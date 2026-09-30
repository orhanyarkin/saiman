package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;

/**
 * The global daily USD cap for model calls (ADR-0011). {@link #assertUnderCap()} runs before every
 * call and {@link #record(Money)} after it, from the usage the provider reported.
 *
 * <p>The cap is a soft guard: calls in flight when the total crosses it still finish, so the
 * overshoot is bounded by concurrency times the cost of one call. The provider-side project limit
 * (ADR-0009) is the hard stop.
 */
public interface CostGuard {

    /**
     * Refuses the next call when the cap is reached.
     *
     * @throws DailyCapExceededException if today's (UTC) total is already at or above the cap
     */
    void assertUnderCap();

    /** Adds {@code cost} to today's (UTC) total and returns the new total. */
    Money record(Money cost);

    /** Today's (UTC) total so far. */
    Money todayTotal();
}
