package io.github.orhanyarkin.saiman.orchestrator.run;

/** Lifecycle of a {@code run} row: QUEUED -> RUNNING (<-> AWAITING_APPROVAL) -> SUCCEEDED | FAILED. */
public enum RunStatus {
    QUEUED,
    RUNNING,
    AWAITING_APPROVAL,
    SUCCEEDED,
    FAILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
