package io.github.orhanyarkin.saiman.sellerapi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The run guard keyed by a service caller (the eval harness, ADR-0025) against a real Redis, and fail-closed. */
class CallerRunGuardTests {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private UnsettledRunGuard guard;

    @BeforeEach
    void connect() {
        if (factory == null) {
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                    SharedContainers.redis().getHost(),
                    SharedContainers.redis().getMappedPort(SharedContainers.REDIS_PORT)));
            factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
        }
        Set<String> keys = redis.keys("seller:runs:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        guard = guardOn(redis);
    }

    private static UnsettledRunGuard guardOn(StringRedisTemplate template) {
        return new UnsettledRunGuard(
                template,
                new LlmRunProperties(
                        2, 30, 100, Duration.ofSeconds(25), Duration.ofSeconds(5), Duration.ofSeconds(120)),
                Clock.systemUTC());
    }

    @Test
    void oneInFlightThenFreedByFinish() {
        guard.tryStartCaller("evals", 1, 60);
        assertThatThrownBy(() -> guard.tryStartCaller("evals", 1, 60)).isInstanceOf(RunLimitExceededException.class);
        guard.finishCaller("evals");
        guard.tryStartCaller("evals", 1, 60);
    }

    @Test
    void theHourlyLimitHolds() {
        for (int i = 0; i < 3; i++) {
            guard.tryStartCaller("evals", 1, 3);
            guard.finishCaller("evals");
        }
        assertThatThrownBy(() -> guard.tryStartCaller("evals", 1, 3)).isInstanceOf(RunLimitExceededException.class);
    }

    @Test
    void callerRunsUseTheirOwnKeysAndNeverTheDayBudget() {
        guard.tryStartCaller("evals", 1, 60);

        assertThat(redis.keys("seller:runs:*"))
                .containsExactlyInAnyOrder("seller:runs:caller:inflight:evals", "seller:runs:caller:hourly:evals");
    }

    @Test
    void malformedCallersAreRejected() {
        for (String caller : new String[] {"Evals", "0xabc", "evals:x", "", "a".repeat(33)}) {
            assertThatThrownBy(() -> guard.tryStartCaller(caller, 1, 60))
                    .as(caller)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anUnreachableStoreFailsClosed() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        dead.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(dead);
        template.afterPropertiesSet();
        try {
            assertThatThrownBy(() -> guardOn(template).tryStartCaller("evals", 1, 60))
                    .isInstanceOf(RunGuardUnavailableException.class);
        } finally {
            dead.destroy();
        }
    }
}
