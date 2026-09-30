/**
 * Human approval of payments above the threshold (ADR-0013). An approval only opens the threshold
 * gate for one payment intent; it never raises a run budget or the daily cap. The {@code approval}
 * table is the source of truth; the in-process {@link
 * io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalWaiter} only wakes the waiting run.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.approval;

import org.jspecify.annotations.NullMarked;
