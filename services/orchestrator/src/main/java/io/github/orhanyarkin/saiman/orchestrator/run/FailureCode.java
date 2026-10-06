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
    /** The run's own LLM cost scope (its model budget) was reached. */
    LLM_BUDGET_EXHAUSTED,
    /**
     * The global daily LLM cap ({@code LLM_DAILY_CAP_USD}) was reached while the run was executing (ADR-0026).
     * Distinct from {@link #LLM_BUDGET_EXHAUSTED}: the run was within its own budget; the day was used up.
     */
    LLM_DAILY_CAP_REACHED,
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
