package io.github.orhanyarkin.saiman.orchestrator.approval;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot of an {@code approval} row: what a human approved (amount, payee, resource) and the
 * decision. {@code decidedBy} is the role of the human who approved or rejected ({@code operator}; null while PENDING
 * and when the approval expired). The full principal name is kept in the database only.
 */
public record ApprovalView(
        UUID id,
        UUID paymentIntentId,
        UUID runId,
        long amountAtomic,
        String payTo,
        String resource,
        ApprovalStatus status,
        Instant requestedAt,
        @Nullable Instant decidedAt,
        Instant expiresAt,
        @Nullable String decidedBy) {}
