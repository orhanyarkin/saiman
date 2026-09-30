package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
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

    @Test
    void passesUnderTheCapAndRefusesAtAndOverIt() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());

        guard.assertUnderCap();
        assertThat(guard.record(Money.usdMicros(999))).isEqualTo(Money.usdMicros(999));
        guard.assertUnderCap();

        guard.record(Money.usdMicros(1));
        assertThatThrownBy(guard::assertUnderCap).isInstanceOf(DailyCapExceededException.class);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(1_000));
    }

    @Test
    void keyIsPerUtcDayAndRollsOverAtMidnight() {
        var clock = new MutableClock(Instant.parse("2999-12-30T23:59:59Z"));
        var guard = new ValkeyCostGuard(template, 500, clock);
        template.delete(guard.todayKey());
        assertThat(guard.todayKey()).isEqualTo("router:cost:2999-12-30");

        guard.record(Money.usdMicros(500));
        assertThatThrownBy(guard::assertUnderCap).isInstanceOf(DailyCapExceededException.class);

        clock.set(Instant.parse("2999-12-31T00:00:00Z"));
        assertThat(guard.todayKey()).isEqualTo("router:cost:2999-12-31");
        template.delete(guard.todayKey());
        guard.assertUnderCap();
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(0));
    }

    @Test
    void theCounterKeyExpires() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());
        guard.record(Money.usdMicros(1));

        Long ttl = template.getExpire(guard.todayKey());

        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofDays(2).toSeconds());
    }

    @Test
    void parallelRecordsAreAtomic() {
        var guard = new ValkeyCostGuard(template, Long.MAX_VALUE, uniqueDay());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                executor.execute(() -> guard.record(Money.usdMicros(3)));
            }
        }
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(600));
    }

    @Test
    void aCorruptCounterFailsClosedWithoutEchoingTheValue() {
        var guard = new ValkeyCostGuard(template, 1_000, uniqueDay());
        template.opsForValue().set(guard.todayKey(), "not-a-number-XYZ");

        assertThatThrownBy(guard::assertUnderCap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a number")
                .hasMessageNotContaining("XYZ");
    }
}
