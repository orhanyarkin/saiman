package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
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

/** {@link ValkeyScopedCostGuard} against a real Valkey: atomic, pinned budget, TTL, fail-closed. */
@Testcontainers
class ValkeyScopedCostGuardTests {

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

    private static String newScope() {
        return "run-" + UUID.randomUUID();
    }

    private static Money micros(long v) {
        return Money.usdMicros(v);
    }

    @Test
    void reserveRefusesWhenTheScopeBudgetWouldBeExceeded() {
        var guard = new ValkeyScopedCostGuard(template);
        String scope = newScope();

        guard.reserve(scope, micros(600), micros(1_000));
        assertThatThrownBy(() -> guard.reserve(scope, micros(401), micros(1_000)))
                .isInstanceOf(ScopeBudgetExceededException.class);
        assertThat(guard.spent(scope)).isEqualTo(micros(600));

        guard.reserve(scope, micros(400), micros(1_000));
        assertThat(guard.spent(scope)).isEqualTo(micros(1_000));
    }

    @Test
    void settleCorrectsToTheActualCostAndNeverGoesBelowZero() {
        var guard = new ValkeyScopedCostGuard(template);
        String scope = newScope();

        var reservation = guard.reserve(scope, micros(500), micros(1_000));
        guard.settle(scope, reservation, micros(120));
        assertThat(guard.spent(scope)).isEqualTo(micros(120));

        guard.release(scope, reservation); // a release after a settle: clamped, never negative
        assertThat(guard.spent(scope).atomicUnits()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void theFirstReservationPinsTheBudgetInValkey() {
        var guard = new ValkeyScopedCostGuard(template);
        String scope = newScope();
        guard.reserve(scope, micros(60), micros(100));

        assertThatThrownBy(() -> guard.reserve(scope, micros(60), micros(9_000_000)))
                .isInstanceOf(ScopeBudgetExceededException.class);
        assertThat(template.opsForHash().get("run:" + scope + ":llm", "budget")).isEqualTo("100");
    }

    @Test
    void theKeyHasATtl() {
        var guard = new ValkeyScopedCostGuard(template);
        String scope = newScope();
        guard.reserve(scope, micros(1), micros(10));

        Long ttl = template.getExpire("run:" + scope + ":llm");

        assertThat(ttl).isPositive().isLessThanOrEqualTo(ValkeyScopedCostGuard.TTL.toSeconds());
    }

    @Test
    void sixteenParallelReservationsGrantExactlyWhatTheBudgetHolds() throws Exception {
        var guard = new ValkeyScopedCostGuard(template);
        String scope = newScope();
        var granted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(16)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        guard.reserve(scope, micros(10_000), micros(50_000));
                        granted.incrementAndGet();
                    } catch (ScopeBudgetExceededException refused) {
                        // expected for the losers
                    }
                }));
            }
            for (var f : futures) {
                f.get();
            }
        }
        assertThat(granted).hasValue(5);
        assertThat(guard.spent(scope)).isEqualTo(micros(50_000));
    }

    @Test
    void aCorruptCounterFailsClosedWithoutEchoingIt() {
        var guard = new ValkeyScopedCostGuard(template);
        String scope = newScope();
        template.opsForHash().put("run:" + scope + ":llm", "spent", "-5");
        template.opsForHash().put("run:" + scope + ":llm", "budget", "100");

        assertThatThrownBy(() -> guard.reserve(scope, micros(1), micros(100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("-5");
    }

    @Test
    void anUnsafeScopeIdIsRefusedBeforeItReachesAKey() {
        var guard = new ValkeyScopedCostGuard(template);

        assertThatThrownBy(() -> guard.reserve("a:b*", micros(1), micros(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.reserve("x".repeat(65), micros(1), micros(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
