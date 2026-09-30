/**
 * Research runs (ADR-0014): {@code POST /api/v1/runs} admits a run (bounded concurrency, budget set
 * once), a virtual thread executes it under a root {@code saiman.run} observation through a {@link
 * io.github.orhanyarkin.saiman.orchestrator.run.ResearchPipeline}, and the run ends with exactly one
 * terminal event whose cost equals the persisted totals.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.run;

import org.jspecify.annotations.NullMarked;
