package io.github.orhanyarkin.saiman.orchestrator.run;

/**
 * Why a run failed: a fixed code, never a message (so no model or seller text can reach a client
 * through it). Stored in {@code run.failure_code} and sent in {@code RUN_FAILED}.
 */
public enum FailureCode {
    /** An unexpected error inside the orchestrator. */
    INTERNAL_ERROR,
    /** No {@link ResearchPipeline} is deployed. */
    PIPELINE_UNAVAILABLE,
    /** The process stopped while the run was unfinished (set at startup by the spend recovery). */
    INTERRUPTED,
    /** The seller's ticker catalogue could not be fetched. */
    CATALOGUE_UNAVAILABLE,
    /** The plan named no ticker the seller knows. */
    INVALID_PLAN,
    /** The run's LLM cost scope or the global daily LLM cap was reached. */
    LLM_BUDGET_EXHAUSTED,
    /** Every model route failed. */
    LLM_UNAVAILABLE,
    /** A model answer failed validation. */
    INVALID_MODEL_OUTPUT,
    /** No tool call produced usable evidence. */
    NO_EVIDENCE,
    /** The final answer cited no chunk the run actually retrieved. */
    NO_VALID_CITATIONS,
    /** The run's wall-clock deadline ({@code saiman.orchestrator.runs.deadline}) passed. */
    RUN_DEADLINE
}
