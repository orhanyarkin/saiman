package io.github.orhanyarkin.saiman.orchestrator.approval;

/** Lifecycle of an {@code approval} row: PENDING, then exactly one of the three decisions. */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}
