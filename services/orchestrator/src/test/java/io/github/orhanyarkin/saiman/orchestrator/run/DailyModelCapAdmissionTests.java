package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.CostGuard;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * ADR-0026: with less of the global daily model budget left than one run's model budget (150000 USD micros), a new run
 * is answered with a 503 Problem Details and a replay offer; nothing is stored. Uses a fake {@link CostGuard}.
 */
@Import(DailyModelCapAdmissionTests.FakeGuardConfiguration.class)
class DailyModelCapAdmissionTests extends RunTestSupport {

    private static final long CAP = 700_000;
    private static final AtomicLong SPENT = new AtomicLong();
    private static final AtomicReference<RuntimeException> BROKEN = new AtomicReference<>();

    @AfterEach
    void reset() {
        SPENT.set(0);
        BROKEN.set(null);
    }

    @Test
    void belowOneRunsModelBudgetTheAnswerIs503WithAReplayOfferAndNoRunIsStored() {
        SPENT.set(CAP - 149_999);

        postRun("{\"question\":\"What changed at THYAO?\"}")
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .value(
                        HttpHeaders.RETRY_AFTER,
                        value -> assertThat(Long.parseLong(value)).isBetween(1L, 86_400L))
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("urn:saiman:problem:llm-daily-cap")
                .jsonPath("$.title")
                .isEqualTo("Daily model budget reached")
                .jsonPath("$.status")
                .isEqualTo(503)
                .jsonPath("$.detail")
                .isEqualTo("The daily model budget is used up. Watch a recorded run instead.")
                .jsonPath("$.code")
                .isEqualTo("LLM_DAILY_CAP_REACHED")
                .jsonPath("$.replayAvailable")
                .isEqualTo(true);
        assertThat(jdbc.sql("SELECT count(*) FROM run").query(Integer.class).single())
                .isZero();
    }

    @Test
    void anUnreadableCounterFailsClosed() {
        BROKEN.set(new IllegalStateException("redis down"));

        postRun("{\"question\":\"What changed at THYAO?\"}")
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals(HttpHeaders.RETRY_AFTER, "60") // an outage may clear soon: not "until midnight"
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("LLM_DAILY_CAP_REACHED");
    }

    @Test
    void redisCallsHaveATimeout(@Autowired Environment environment) {
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("2s");
    }

    @Test
    void withExactlyOneRunsBudgetLeftTheRunIsAdmitted() {
        SPENT.set(CAP - 150_000);

        assertThat(startRun("What changed at THYAO?", null)).containsKey("runId");
    }

    @Test
    void spendReportsTheLlmDayAsIntegers() {
        SPENT.set(123_456);

        http.get()
                .uri("/api/v1/spend")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.llmDay.spentUsdMicros")
                .isEqualTo(123_456)
                .jsonPath("$.llmDay.capUsdMicros")
                .isEqualTo(CAP);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGuardConfiguration {

        @Bean
        CostGuard costGuard() {
            return new CostGuard() {
                @Override
                public Reservation reserve(Money estimate) {
                    return new Reservation(java.time.LocalDate.now(), estimate);
                }

                @Override
                public void settle(Reservation reservation, Money actual) {}

                @Override
                public Money todayTotal() {
                    RuntimeException broken = BROKEN.get();
                    if (broken != null) {
                        throw broken;
                    }
                    return Money.usdMicros(SPENT.get());
                }
            };
        }
    }
}
