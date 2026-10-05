package io.github.orhanyarkin.saiman.ledger.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Reconciliation history, newest first, without items ({@code GET /api/v1/reconciliation/runs}). */
public record ReconciliationRunList(List<Run> items) {

    public ReconciliationRunList {
        items = List.copyOf(items);
    }

    /**
     * One run's header and counters; its items are in the run's report.
     *
     * @param status RUNNING, COMPLETED, PARTIAL or FAILED
     * @param safeBlock the Base Sepolia block the run treated as final, null if it never read one
     */
    public record Run(
            UUID runId,
            String status,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Long safeBlock,
            ReconciliationReport.Summary summary) {}
}
