package io.github.orhanyarkin.saiman.shared.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunContractTests {

    @Test
    void runCostSumsUsdcAndUsdMicrosBecauseBothHaveSixDecimals() {
        var cost = RunCost.of(new Money(30_000, "USDC", 6), Money.usdMicros(12_500));

        assertThat(cost.totalUsd()).isEqualTo(Money.usdMicros(42_500));
    }

    @Test
    void runCostRejectsTheWrongAssets() {
        assertThatThrownBy(() -> RunCost.of(Money.usdMicros(1), Money.usdMicros(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunCost.of(new Money(1, "USDC", 6), new Money(1, "USDC", 6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventIdIsRunIdAndSeqAndSeqStartsAtOne() {
        UUID run = UUID.randomUUID();
        var event = new RunEvent(
                run, 3, RunEventType.STEP_STARTED, Instant.EPOCH, new RunEventData.StepChanged(AgentStep.PLANNER));

        assertThat(event.eventId()).isEqualTo(run + ":3");
        assertThatThrownBy(() -> new RunEvent(
                        run,
                        0,
                        RunEventType.STEP_STARTED,
                        Instant.EPOCH,
                        new RunEventData.StepChanged(AgentStep.PLANNER)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyCompletedAndFailedAreTerminal() {
        for (RunEventType type : RunEventType.values()) {
            assertThat(type.terminal())
                    .isEqualTo(type == RunEventType.RUN_COMPLETED || type == RunEventType.RUN_FAILED);
        }
    }
}
