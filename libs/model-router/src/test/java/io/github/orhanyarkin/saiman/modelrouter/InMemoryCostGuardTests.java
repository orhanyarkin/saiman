package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryCostGuardTests {

    private static Money micros(long v) {
        return Money.usdMicros(v);
    }

    @Test
    void reserveRefusesWhenReservationWouldExceedCap() {
        var guard = new InMemoryCostGuard(1_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));

        guard.reserve(micros(600));
        assertThatThrownBy(() -> guard.reserve(micros(401))).isInstanceOf(DailyCapExceededException.class);
        assertThat(guard.todayTotal()).isEqualTo(micros(600)); // the refused reservation left no trace

        guard.reserve(micros(400)); // exactly at the cap is allowed
        assertThat(guard.todayTotal()).isEqualTo(micros(1_000));
        assertThatThrownBy(() -> guard.reserve(micros(1))).isInstanceOf(DailyCapExceededException.class);
    }

    @Test
    void reservationIsAdjustedToActualAfterTheCall() {
        var guard = new InMemoryCostGuard(1_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));

        var reservation = guard.reserve(micros(500));
        guard.settle(reservation, micros(120)); // cheaper than estimated: frees the difference
        assertThat(guard.todayTotal()).isEqualTo(micros(120));

        var second = guard.reserve(micros(100));
        guard.settle(second, micros(300)); // dearer than estimated: settled at the truth
        assertThat(guard.todayTotal()).isEqualTo(micros(420));
    }

    @Test
    void releaseGivesTheWholeReservationBackAndTotalsNeverGoBelowZero() {
        var guard = new InMemoryCostGuard(1_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
        var reservation = guard.reserve(micros(300));

        guard.release(reservation);
        assertThat(guard.todayTotal()).isEqualTo(micros(0));

        guard.release(reservation); // double release must not go negative
        assertThat(guard.todayTotal()).isEqualTo(micros(0));
    }

    @Test
    void theCounterRollsOverAtUtcMidnightAndSettlesOnTheReservedDay() {
        var clock = new MutableClock(Instant.parse("2026-09-29T23:59:59Z"));
        var guard = new InMemoryCostGuard(1_000, clock);
        var reservation = guard.reserve(micros(1_000));
        assertThatThrownBy(() -> guard.reserve(micros(1))).isInstanceOf(DailyCapExceededException.class);

        clock.set(Instant.parse("2026-09-30T00:00:00Z"));

        guard.reserve(micros(1)); // a new day, a fresh counter
        assertThat(guard.todayTotal()).isEqualTo(micros(1));
        guard.settle(reservation, micros(0)); // yesterday's reservation does not touch today
        assertThat(guard.todayTotal()).isEqualTo(micros(1));
    }

    @Test
    void twoHundredVirtualThreadCallersCannotOverspendTheCap() {
        long cap = 10_000;
        var guard = new InMemoryCostGuard(cap, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
        var granted = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                executor.execute(() -> {
                    try {
                        guard.reserve(micros(100));
                        granted.incrementAndGet();
                    } catch (DailyCapExceededException expected) {
                        // refused
                    }
                });
            }
        }
        assertThat(granted.get()).isEqualTo(100);
        assertThat(guard.todayTotal().atomicUnits()).isLessThanOrEqualTo(cap);
    }
}
