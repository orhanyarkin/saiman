package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class InMemoryCostGuardTests {

    @Test
    void passesUnderTheCapAndRefusesAtAndOverIt() {
        var guard = new InMemoryCostGuard(1_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));

        guard.assertUnderCap();
        assertThat(guard.record(Money.usdMicros(999))).isEqualTo(Money.usdMicros(999));
        guard.assertUnderCap(); // 999 < 1000

        guard.record(Money.usdMicros(1)); // exactly at the cap
        assertThatThrownBy(guard::assertUnderCap).isInstanceOf(DailyCapExceededException.class);

        guard.record(Money.usdMicros(500)); // over the cap
        assertThatThrownBy(guard::assertUnderCap).isInstanceOf(DailyCapExceededException.class);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(1_500));
    }

    @Test
    void theCounterRollsOverAtUtcMidnight() {
        var clock = new MutableClock(Instant.parse("2026-09-29T23:59:59Z"));
        var guard = new InMemoryCostGuard(1_000, clock);
        guard.record(Money.usdMicros(1_000));
        assertThatThrownBy(guard::assertUnderCap).isInstanceOf(DailyCapExceededException.class);

        clock.set(Instant.parse("2026-09-30T00:00:00Z"));

        guard.assertUnderCap();
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(0));
    }

    @Test
    void concurrentRecordsDoNotLoseUpdates() {
        var guard = new InMemoryCostGuard(Long.MAX_VALUE, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1_000; i++) {
                executor.execute(() -> guard.record(Money.usdMicros(7)));
            }
        }
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(7_000));
    }
}
