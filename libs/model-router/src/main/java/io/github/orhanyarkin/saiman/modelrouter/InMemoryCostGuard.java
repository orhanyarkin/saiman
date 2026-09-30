package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-process daily counter, used when no {@code StringRedisTemplate} exists (tests, single-process
 * demos). Not shared between services, so the real cap is only enforced by {@link
 * ValkeyCostGuard}.
 */
public final class InMemoryCostGuard implements CostGuard {

    private final long capUsdMicros;
    private final Clock clock;
    private final ConcurrentHashMap<LocalDate, AtomicLong> totals = new ConcurrentHashMap<>();

    public InMemoryCostGuard(long capUsdMicros, Clock clock) {
        this.capUsdMicros = capUsdMicros;
        this.clock = clock;
    }

    @Override
    public void assertUnderCap() {
        if (counter().get() >= capUsdMicros) {
            throw new DailyCapExceededException("daily model cost cap reached (" + capUsdMicros + " USD micros)");
        }
    }

    @Override
    public Money record(Money cost) {
        return Money.usdMicros(counter().addAndGet(cost.atomicUnits()));
    }

    @Override
    public Money todayTotal() {
        return Money.usdMicros(counter().get());
    }

    private AtomicLong counter() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        totals.keySet().removeIf(day -> day.isBefore(today));
        return totals.computeIfAbsent(today, day -> new AtomicLong());
    }
}
