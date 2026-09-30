package io.github.orhanyarkin.saiman.shared.run;

/** Event kinds of schema {@code agent.run-step.v1}; each maps to one {@link RunEventData} payload. */
public enum RunEventType {
    RUN_STARTED,
    STEP_STARTED,
    PLAN_CREATED,
    TOOL_CALL_REQUESTED,
    PAYMENT_APPROVAL_REQUIRED,
    PAYMENT_APPROVAL_DECIDED,
    PAYMENT_DENIED,
    PAYMENT_SETTLED,
    PAYMENT_AMBIGUOUS,
    TOOL_CALL_COMPLETED,
    MODEL_CALL_COMPLETED,
    STEP_COMPLETED,
    RUN_COMPLETED,
    RUN_FAILED;

    /** @return true if no event follows this one in a run */
    public boolean terminal() {
        return this == RUN_COMPLETED || this == RUN_FAILED;
    }
}
