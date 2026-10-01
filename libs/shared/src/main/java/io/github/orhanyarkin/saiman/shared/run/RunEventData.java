package io.github.orhanyarkin.saiman.shared.run;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of {@link RunEvent}. Nothing here is free model text except {@link Report#answer()}
 * (plain text, rendered as text by every consumer) and citation titles, which code rebuilds from the
 * retrieved evidence. Idempotency keys, nonces and signatures never appear (only the tx hash).
 */
public sealed interface RunEventData {

    /** RUN_STARTED. */
    record RunStarted(String question, Money budget) implements RunEventData {}

    /** STEP_STARTED and STEP_COMPLETED. */
    record StepChanged(AgentStep step) implements RunEventData {}

    /** PLAN_CREATED: tickers and tasks were validated by code against the seller's ticker catalogue. */
    record PlanCreated(List<String> tickers, List<String> tasks) implements RunEventData {
        public PlanCreated {
            tickers = List.copyOf(tickers);
            tasks = List.copyOf(tasks);
        }
    }

    /** TOOL_CALL_REQUESTED: {@code arguments} is the validated, code-rendered form, not raw model JSON. */
    record ToolCallRequested(String tool, String arguments) implements RunEventData {}

    /** PAYMENT_APPROVAL_REQUIRED: a payment above the approval threshold waits for a human. */
    record PaymentApprovalRequired(
            UUID approvalId, UUID paymentIntentId, Money amount, String payTo, String resource, Instant expiresAt)
            implements RunEventData {}

    /** PAYMENT_APPROVAL_DECIDED: decision is {@code APPROVED}, {@code REJECTED} or {@code EXPIRED}. */
    record PaymentApprovalDecided(UUID approvalId, String decision) implements RunEventData {}

    /** PAYMENT_DENIED: refused before signing. */
    record PaymentDenied(DenyReason reason, Money amount) implements RunEventData {}

    /** PAYMENT_SETTLED. */
    record PaymentSettled(UUID paymentIntentId, Money amount, String txHash) implements RunEventData {}

    /** PAYMENT_AMBIGUOUS: signed but the outcome is unknown; the reservation stays counted (held). */
    record PaymentAmbiguous(UUID paymentIntentId, Money amount) implements RunEventData {}

    /** TOOL_CALL_COMPLETED. */
    record ToolCallCompleted(String tool, boolean paid, int citationCount) implements RunEventData {}

    /** MODEL_CALL_COMPLETED: one LLM round trip, costed by the router. */
    record ModelCallCompleted(
            AgentStep step, String tier, String model, long inputTokens, long outputTokens, Money costUsd)
            implements RunEventData {}

    /** RUN_COMPLETED. */
    record RunCompleted(Report report, RunCost cost) implements RunEventData {}

    /** RUN_FAILED: a fixed code (a {@code FailureCode} name of the orchestrator, for example {@code RUN_DEADLINE} or {@code INTERRUPTED}), never a message. */
    record RunFailed(String failureCode, RunCost costSoFar) implements RunEventData {}

    /** The final answer; every citation was validated against the evidence the run actually retrieved. */
    record Report(String answer, List<Citation> citations) {
        public Report {
            citations = List.copyOf(citations);
        }
    }

    /** A citation rebuilt by code from a retrieved chunk. */
    record Citation(String chunkId, String sourceUrl, String title) {}
}
