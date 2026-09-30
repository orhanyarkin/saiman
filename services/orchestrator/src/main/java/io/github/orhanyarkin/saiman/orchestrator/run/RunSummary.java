package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/runs/{runId}}: status, budget and spend of one run.
 *
 * @param reserved payments reserved and not yet settled, including held ones (outcome unknown)
 * @param committed settled payments
 * @param cost settled payments plus LLM cost so far (the final cost once the run is finished)
 * @param failureCode a fixed {@link FailureCode} name for a FAILED run
 * @param traceId the run's own trace (its root {@code saiman.run} span)
 * @param report the final report of a SUCCEEDED run
 */
public record RunSummary(
        UUID runId,
        RunStatus status,
        String question,
        Money budget,
        Money reserved,
        Money committed,
        RunCost cost,
        @Nullable String failureCode,
        @Nullable String traceId,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt,
        RunEventData.@Nullable Report report) {}
