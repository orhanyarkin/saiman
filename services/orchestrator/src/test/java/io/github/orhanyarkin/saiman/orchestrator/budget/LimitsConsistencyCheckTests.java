package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Startup cross-checks: a run budget above the daily cap fails; an LLM budget the router clamps warns. */
class LimitsConsistencyCheckTests {

    @Test
    void aRunBudgetAboveTheDailyCapIsAStartupError() {
        assertThat(LimitsConsistencyCheck.llmBudgetAboveDailyCap(150_000, 700_000))
                .isNull();
        assertThat(LimitsConsistencyCheck.llmBudgetAboveDailyCap(700_000, 700_000))
                .isNull();
        assertThat(LimitsConsistencyCheck.llmBudgetAboveDailyCap(700_001, 700_000))
                .contains("no run could ever start");
    }

    @Test
    void aDefaultRunBudgetAboveTheDailyCapFailsStartup() {
        assertThatThrownBy(() -> new SpendProperties(40_000, 50_000, 200_000, 10_000, Duration.ofMinutes(5), 4, 6, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-run-budget-atomic must be <= daily-cap-atomic");
        assertThat(new SpendProperties(50_000, 50_000, 200_000, 10_000, Duration.ofMinutes(5), 4, 6, 25)
                        .defaultRunBudgetAtomic())
                .isEqualTo(50_000);
    }

    @Test
    void anLlmBudgetWithinTheRoutersScopeMaximumIsQuiet() {
        assertThat(LimitsConsistencyCheck.llmBudgetWarning(150_000, 200_000)).isNull();
        assertThat(LimitsConsistencyCheck.llmBudgetWarning(200_000, 200_000)).isNull();
    }

    @Test
    void anHourlyPaidCallLimitAtOrBelowTheSellersIsQuiet() {
        assertThat(LimitsConsistencyCheck.hourlyPaidCallsWarning(25, 30)).isNull();
        assertThat(LimitsConsistencyCheck.hourlyPaidCallsWarning(30, 30)).isNull();
    }

    @Test
    void anHourlyPaidCallLimitAboveTheSellersWarns() {
        assertThat(LimitsConsistencyCheck.hourlyPaidCallsWarning(31, 30))
                .contains("max-paid-calls-per-hour (31)")
                .contains("max-paid-calls-per-hour-at-seller (30)")
                .contains("429");
    }

    @Test
    void anLlmBudgetAboveTheRoutersScopeMaximumWarnsThatItIsClamped() {
        assertThat(LimitsConsistencyCheck.llmBudgetWarning(300_000, 200_000))
                .contains("llm-budget-usd-micros (300000)")
                .contains("max-scope-budget-usd-micros (200000)")
                .contains("clamps");
    }
}
