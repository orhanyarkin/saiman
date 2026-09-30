package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-process daily counter, used only when explicitly requested with {@code
 * saiman.router.cost-guard=memory} (tests, single-process demos). Not shared between services, so a
 * shared budget is only enforced by {@link ValkeyCostGuard}. All operations run under one lock.
 */
public final class InMemoryCostGuard implements CostGuard {

    private final long capUsdMicros;
    private final Clock clock;
    private final Map<LocalDate, Long> totals = new HashMap<>();

    public InMemoryCostGuard(long capUsdMicros, Clock clock) {
        this.capUsdMicros = capUsdMicros;
        this.clock = clock;
    }

    @Override
    public synchronized Reservation reserve(Money estimate) {
        LocalDate today = today();
        long total = totals.getOrDefault(today, 0L);
        long next;
        try {
            next = Math.addExact(total, estimate.atomicUnits());
        } catch (ArithmeticException e) {
            throw new DailyCapExceededException(capMessage());
        }
        if (next > capUsdMicros) {
            throw new DailyCapExceededException(capMessage());
        }
        totals.put(today, next);
        return new Reservation(today, estimate);
    }

    @Override
    public synchronized void settle(Reservation reservation, Money actual) {
        long delta = actual.atomicUnits() - reservation.estimate().atomicUnits();
        long current = totals.getOrDefault(reservation.day(), 0L);
        totals.put(reservation.day(), Math.max(0L, saturatingAdd(current, delta)));
    }

    @Override
    public synchronized Money todayTotal() {
        return Money.usdMicros(totals.getOrDefault(today(), 0L));
    }

    private LocalDate today() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        totals.keySet().removeIf(day -> day.isBefore(today));
        return today;
    }

    private String capMessage() {
        return "daily model cost cap reached (" + capUsdMicros + " USD micros)";
    }

    private static long saturatingAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return b > 0 ? Long.MAX_VALUE : 0L;
        }
    }
}
