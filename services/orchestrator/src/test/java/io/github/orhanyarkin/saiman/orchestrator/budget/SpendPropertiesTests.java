package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SpendPropertiesTests {

    private static SpendProperties props(long defaultBudget, long maxBudget) {
        return new SpendProperties(1_000_000, defaultBudget, maxBudget, 20_000, Duration.ofMinutes(5), 4, 6, 25);
    }

    @Test
    void maxRunBudgetMustNotBeBelowTheDefault() {
        assertThatThrownBy(() -> props(50_000, 40_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-run-budget-atomic");
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new SpendProperties(0, 1, 1, 1, Duration.ofMinutes(1), 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpendProperties(1, 1, 1, 1, Duration.ZERO, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRunBudgetIsTheDefaultOrARequestUpToTheMaximum() {
        SpendProperties spend = props(50_000, 200_000);
        assertThat(spend.resolveRunBudget(null)).isEqualTo(50_000);
        assertThat(spend.resolveRunBudget(200_000L)).isEqualTo(200_000);
        assertThatThrownBy(() -> spend.resolveRunBudget(200_001L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spend.resolveRunBudget(0L)).isInstanceOf(IllegalArgumentException.class);
    }
}
