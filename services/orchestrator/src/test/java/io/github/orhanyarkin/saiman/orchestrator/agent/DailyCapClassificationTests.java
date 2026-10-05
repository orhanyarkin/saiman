package io.github.orhanyarkin.saiman.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.DailyCapExceededException;
import io.github.orhanyarkin.saiman.modelrouter.ScopeBudgetExceededException;
import io.github.orhanyarkin.saiman.orchestrator.run.FailureCode;
import org.junit.jupiter.api.Test;

/** ADR-0026: the run's own model budget and the global daily cap fail a run with different codes. */
class DailyCapClassificationTests {

    @Test
    void aDailyCapRefusalMidRunIsLlmDailyCapReached() {
        var failure = new RuntimeException("model call failed", new DailyCapExceededException("cap"));

        assertThat(AgentPipeline.classify(failure)).isEqualTo(FailureCode.LLM_DAILY_CAP_REACHED);
    }

    @Test
    void theRunsOwnScopeBudgetStaysLlmBudgetExhausted() {
        var failure = new RuntimeException("model call failed", new ScopeBudgetExceededException("scope"));

        assertThat(AgentPipeline.classify(failure)).isEqualTo(FailureCode.LLM_BUDGET_EXHAUSTED);
    }
}
