package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.LocalDate;

/**
 * The global daily USD cap for model calls (ADR-0011), enforced by <em>reservation</em>: before a
 * call the router reserves its worst-case cost with {@link #reserve(Money)}, an atomic
 * check-and-add that refuses when the day's total plus the estimate would exceed the cap. After the
 * call {@link #settle(Reservation, Money)} corrects the reservation to the actual cost.
 *
 * <p>Guarantee: the sum of settled costs and outstanding reservations never exceeds the cap, so
 * concurrent callers cannot overspend it. The remaining error is the estimate's own: a call whose
 * actual cost exceeds its worst-case estimate (for example tool schemas the estimate does not see)
 * is settled at the truth and can push the total over the cap. When the outcome of a call is unknown
 * (it failed after being sent, a stream was cancelled without usage) the reservation simply stays,
 * so the counter errs on the high side. The provider-side project limit (ADR-0009) is still the
 * last resort.
 */
public interface CostGuard {

    /** A reservation against one UTC day's counter. */
    record Reservation(LocalDate day, Money estimate) {}

    /**
     * Atomically adds {@code estimate} to today's (UTC) total unless that would exceed the cap.
     *
     * @throws DailyCapExceededException if today's total plus {@code estimate} would exceed the cap
     */
    Reservation reserve(Money estimate);

    /**
     * Replaces the reservation by the actual cost (adds {@code actual - estimate}, which may be
     * negative). The day's total never goes below zero. Never refuses: the money is already spent.
     */
    void settle(Reservation reservation, Money actual);

    /** Gives a reservation back completely (the request provably never left this process). */
    default void release(Reservation reservation) {
        settle(reservation, reservation.estimate().zero());
    }

    /** Today's (UTC) total so far: settled costs plus outstanding reservations. */
    Money todayTotal();
}
