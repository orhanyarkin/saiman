package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.shared.run.RunEventData;

/**
 * Records one LLM round trip of a run: adds its USD cost to {@code run.llm_cost_usd_micros} and
 * emits {@code MODEL_CALL_COMPLETED} in the same transaction, so the run's persisted LLM cost is
 * always the sum of its model-call events.
 */
@FunctionalInterface
public interface ModelCallRecorder {

    /**
     * Records one model call.
     *
     * @throws IllegalArgumentException if {@code call.costUsd()} is not 6-decimal USD
     */
    void record(RunEventData.ModelCallCompleted call);
}
