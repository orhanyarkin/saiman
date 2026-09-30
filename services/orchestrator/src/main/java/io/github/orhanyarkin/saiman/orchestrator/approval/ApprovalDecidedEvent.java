package io.github.orhanyarkin.saiman.orchestrator.approval;

import java.util.UUID;

/**
 * Published inside the transaction that decided an approval; {@link ApprovalWaiter} receives it
 * only after that transaction committed.
 */
record ApprovalDecidedEvent(UUID approvalId, ApprovalStatus status) {}
