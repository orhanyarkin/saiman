package io.github.orhanyarkin.saiman.sellerapi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The guard's atomic accounting against a real Redis, and its fail-closed behaviour without one. */
class UnsettledRunGuardTests {

    private static final String PAYER = "0xAbCdEf0000000000000000000000000000000001";

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
        guard = guardWith(2, 5, 4);
    }

    private static UnsettledRunGuard guardWith(int inFlight, int perHour, int unsettled) {
        return new UnsettledRunGuard(
                redis,
                new LlmRunProperties(
                        inFlight,
                        perHour,
                        unsettled,
                        Duration.ofSeconds(25),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(120)),
                Clock.systemUTC());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static String unsettled() {
        String value = redis.opsForValue().get("seller:runs:unsettled:" + LocalDate.now(ZoneOffset.UTC));
        return value == null ? "0" : value;
    }

    private static X402PaymentSettledEvent settledEvent() {
        return new X402PaymentSettledEvent(
                UUID.randomUUID(),
                "/v1/x",
                new PaymentRequirements("exact", "eip155:84532", "1", "0xa", "0xb", 60, java.util.Map.of()),
                PAYER,
                "0x" + "0".repeat(64),
                "1",
                "1",
                PAYER,
                "0x" + "1".repeat(64),
                Instant.now());
    }

    @Test
    void refusesAThirdConcurrentRunAndFinishFreesTheSlot() {
        guard.tryStart(PAYER);
        guard.tryStart(PAYER.toLowerCase());
        assertThatThrownBy(() -> guard.tryStart(PAYER)).isInstanceOf(RunLimitExceededException.class);

        guard.finish(PAYER);
        guard.tryStart(PAYER);
        assertThat(unsettled()).isEqualTo("3");
    }

    @Test
    void aRefusedStartReservesNothing() {
        guard.tryStart(PAYER);
        guard.tryStart(PAYER);
        assertThatThrownBy(() -> guard.tryStart(PAYER)).isInstanceOf(RunLimitExceededException.class);
        assertThat(unsettled()).isEqualTo("2");
    }

    @Test
    void theHourlyLimitCountsStartedRunsNotRunningOnes() {
        UnsettledRunGuard guard = guardWith(2, 5, 100);
        for (int i = 0; i < 5; i++) {
            guard.tryStart(PAYER);
            guard.finish(PAYER);
        }
        assertThatThrownBy(() -> guard.tryStart(PAYER)).isInstanceOf(RunLimitExceededException.class);
        // Another payer is unaffected by this payer's hour.
        guard.tryStart("0x0000000000000000000000000000000000000002");
    }

    @Test
    void theUnsettledBudgetIsSharedAcrossPayers() {
        for (int i = 0; i < 4; i++) {
            String payer = "0x00000000000000000000000000000000000000" + String.format("%02d", 10 + i);
            guard.tryStart(payer);
            guard.finish(payer);
        }
        assertThatThrownBy(() -> guard.tryStart("0x0000000000000000000000000000000000000099"))
                .isInstanceOf(RunLimitExceededException.class);
    }

    @Test
    void aSettledRunLeavesTheDayCounterAloneButKeepsThePerPayerLimits() {
        // The day's unsettled budget (4) is used up by other payers.
        for (int i = 0; i < 4; i++) {
            String payer = "0x00000000000000000000000000000000000000" + String.format("%02d", 20 + i);
            guard.tryStart(payer);
            guard.finish(payer);
        }
        assertThat(unsettled()).isEqualTo("4");

        // Settled (upfront) runs neither count nor are refused by the day budget...
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        guard.tryStart(PAYER, true);
        guard.tryStart(PAYER, true);
        assertThat(unsettled()).isEqualTo("4");
        // ...nor mark the request, so a settlement event gives nothing back.
        guard.onSettled(settledEvent());
        assertThat(unsettled()).isEqualTo("4");

        // The per-payer in-flight cap (2) still applies.
        assertThatThrownBy(() -> guard.tryStart(PAYER, true)).isInstanceOf(RunLimitExceededException.class);
        guard.finish(PAYER);
        guard.finish(PAYER);

        // And the per-payer hourly cap (5): two more settled runs fit, the next does not.
        UnsettledRunGuard hourly = guardWith(10, 5, 4);
        for (int i = 0; i < 3; i++) {
            hourly.tryStart(PAYER, true);
            hourly.finish(PAYER);
        }
        assertThatThrownBy(() -> hourly.tryStart(PAYER, true)).isInstanceOf(RunLimitExceededException.class);
        assertThat(unsettled()).isEqualTo("4");
    }

    @Test
    void finishNeverGoesBelowZero() {
        for (int i = 0; i < 5; i++) {
            guard.finish(PAYER);
        }
        guard.tryStart(PAYER);
        guard.tryStart(PAYER);
        assertThatThrownBy(() -> guard.tryStart(PAYER)).isInstanceOf(RunLimitExceededException.class);
    }

    @Test
    void aSettlementGivesTheSlotBackOnlyForARequestThatStartedARunAndNeverBelowZero() {
        // A settlement of a request that started no run (a cache hit) changes nothing.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        guard.tryStart(PAYER); // marks THIS request as having started a run
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        guard.onSettled(settledEvent());
        assertThat(unsettled()).isEqualTo("1");

        // The request that did start a run gives its slot back, once per event.
        ServletRequestAttributes starter = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(starter);
        guard.tryStart(PAYER);
        guard.onSettled(settledEvent());
        assertThat(unsettled()).isEqualTo("1");

        // Clamped: repeated settlements can not drive the counter negative.
        for (int i = 0; i < 5; i++) {
            guard.onSettled(settledEvent());
        }
        assertThat(unsettled()).isEqualTo("0");
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        dead.afterPropertiesSet();
        try {
            StringRedisTemplate template = new StringRedisTemplate(dead);
            template.afterPropertiesSet();
            UnsettledRunGuard broken = new UnsettledRunGuard(
                    template,
                    new LlmRunProperties(
                            2, 30, 100, Duration.ofSeconds(25), Duration.ofSeconds(5), Duration.ofSeconds(120)),
                    Clock.systemUTC());

            assertThatThrownBy(() -> broken.tryStart(PAYER)).isInstanceOf(RunGuardUnavailableException.class);
            broken.finish(PAYER); // never throws
        } finally {
            dead.destroy();
        }
    }
}
