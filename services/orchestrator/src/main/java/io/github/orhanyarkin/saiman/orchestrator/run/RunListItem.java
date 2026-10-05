package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code GET /api/v1/runs}: a run without its report, trace or failure detail.
 *
 * @param reserved payments reserved and not yet settled, including held ones
 * @param committed settled payments
 * @param cost settled payments plus LLM cost so far (always known: both counters are columns of the run)
 * @param pendingApprovals approvals of this run that still wait for a human
 */
public record RunListItem(
        UUID runId,
        RunStatus status,
        String question,
        Money budget,
        Money committed,
        Money reserved,
        RunCost cost,
        Instant createdAt,
        @Nullable Instant finishedAt,
        int pendingApprovals) {}
