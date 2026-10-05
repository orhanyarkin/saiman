package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;

/**
 * Where today's (UTC) global model-cost cap stands (ADR-0011, ADR-0026): what was spent or is reserved so far, and the
 * cap. A snapshot: another caller may reserve right after it is read, so it is for deciding whether to <em>start</em>
 * something (a public run, or a replay instead); the cap itself is still enforced by reservation on every model call.
 *
 * @param spent settled costs plus outstanding reservations of the day, USD micro-dollars
 * @param cap the daily cap, USD micro-dollars
 */
public record DailyCapStatus(Money spent, Money cap) {

    /** What is left before the cap, never negative (a call settled above its estimate can push {@code spent} past it). */
    public Money remaining() {
        return spent.isGreaterThan(cap) ? cap.zero() : cap.minus(spent);
    }

    /** The cap is used up: nothing is left. */
    public boolean isReached() {
        return remaining().isZero();
    }

    /** The status a router reports when it cannot read the counter: fully spent, so callers fail closed. */
    public static DailyCapStatus reached(Money cap) {
        return new DailyCapStatus(cap, cap);
    }
}
