package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link ValkeyCostGuard} against a real Valkey server. The connection is wired by hand, as in the
 * x402 starter's nonce-store tests.
 */
@Testcontainers
class ValkeyCostGuardTests {

    @Container
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.2-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(VALKEY.getHost(), VALKEY.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    /** A distinct far-future day per call keeps tests independent inside one shared container. */
    private static MutableClock uniqueDay() {
        int n = Math.floorMod(UUID.randomUUID().hashCode(), 300_000);
        return new MutableClock(Instant.parse("2100-01-01T12:00:00Z").plus(Duration.ofDays(n)));
    }

    private static Money micros(long v) {
        return Money.usdMicros(v);
    }

    @Test
    void reserveRefusesWhenReservationWouldExceedCap() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());

        guard.reserve(micros(600));
        assertThatThrownBy(() -> guard.reserve(micros(401))).isInstanceOf(DailyCapExceededException.class);
        assertThat(guard.todayTotal()).isEqualTo(micros(600));

        guard.reserve(micros(400));
        assertThat(guard.todayTotal()).isEqualTo(micros(1_000));
        assertThatThrownBy(() -> guard.reserve(micros(1))).isInstanceOf(DailyCapExceededException.class);
    }

    @Test
    void reservationIsAdjustedToActualAfterTheCall() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());

        var reservation = guard.reserve(micros(500));
        guard.settle(reservation, micros(120));
        assertThat(guard.todayTotal()).isEqualTo(micros(120));

        var second = guard.reserve(micros(100));
        guard.settle(second, micros(300));
        assertThat(guard.todayTotal()).isEqualTo(micros(420));
    }

    @Test
    void settleNeverTakesTheDayBelowZero() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());
        var reservation = guard.reserve(micros(300));

        guard.release(reservation);
        guard.release(reservation);

        assertThat(guard.todayTotal()).isEqualTo(micros(0));
        assertThat(template.getExpire(guard.todayKey())).isPositive(); // the clamp keeps the TTL
    }

    @Test
    void keyIsPerUtcDayAndRollsOverAtMidnight() {
        var clock = new MutableClock(Instant.parse("2999-12-30T23:59:59Z"));
        var guard = new ValkeyCostGuard(template, 500, clock);
        template.delete(guard.todayKey());
        assertThat(guard.todayKey()).isEqualTo("router:cost:2999-12-30");

        guard.reserve(micros(500));
        assertThatThrownBy(() -> guard.reserve(micros(1))).isInstanceOf(DailyCapExceededException.class);

        clock.set(Instant.parse("2999-12-31T00:00:00Z"));
        assertThat(guard.todayKey()).isEqualTo("router:cost:2999-12-31");
        template.delete(guard.todayKey());
        guard.reserve(micros(1));
        assertThat(guard.todayTotal()).isEqualTo(micros(1));
    }

    @Test
    void theCounterKeyExpiresEvenWhenTheFirstReservationIsRefused() {
        var guard = new ValkeyCostGuard(template, 10, uniqueDay());
        assertThatThrownBy(() -> guard.reserve(micros(11))).isInstanceOf(DailyCapExceededException.class);

        Long ttl = template.getExpire(guard.todayKey());

        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofDays(2).toSeconds());
        assertThat(guard.todayTotal()).isEqualTo(micros(0));
    }

    @Test
    void twoHundredVirtualThreadCallersCannotOverspendTheCap() {
        long cap = 10_000;
        var guard = new ValkeyCostGuard(template, cap, uniqueDay());
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

    @Test
    void aCorruptCounterFailsClosedWithoutEchoingTheValue() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());
        template.opsForValue().set(guard.todayKey(), "not-a-number-XYZ");

        assertThatThrownBy(guard::todayTotal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a number")
                .hasMessageNotContaining("XYZ");
        assertThatThrownBy(() -> guard.reserve(micros(1)))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(DailyCapExceededException.class)
                .hasMessageNotContaining("XYZ");
    }

    @Test
    void aNegativeCounterIsCorruptAndFailsClosed() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());
        template.opsForValue().set(guard.todayKey(), "-500");

        assertThatThrownBy(guard::todayTotal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> guard.reserve(micros(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative");
        assertThat(template.opsForValue().get(guard.todayKey())).isEqualTo("-500"); // the failed reserve undid itself
    }
}
