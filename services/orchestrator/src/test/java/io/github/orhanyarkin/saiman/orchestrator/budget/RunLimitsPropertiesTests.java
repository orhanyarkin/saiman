package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunLimitsPropertiesTests {

    @ParameterizedTest
    @ValueSource(longs = {0, 999, 24 * 3_600_000L + 1})
    void aDeadlineOutsideOneSecondToOneDayFailsStartup(long millis) {
        assertThatThrownBy(() -> new RunLimitsProperties(2, 150_000, Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    void theShippedDeadlineIsAccepted() {
        assertThat(new RunLimitsProperties(2, 150_000, Duration.ofMinutes(15)).deadline())
                .isEqualTo(Duration.ofMinutes(15));
    }
}
