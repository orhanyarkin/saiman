package io.github.orhanyarkin.saiman.orchestrator.approval;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot of an {@code approval} row: what a human approved (amount, payee, resource) and the
 * decision. {@code decidedBy} is the authenticated principal name of the human who approved or rejected (null while
 * PENDING and when the approval expired).
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
