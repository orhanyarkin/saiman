package io.github.orhanyarkin.saiman.ledger.api;

import java.util.UUID;

/** The 202 body of {@code POST /api/v1/reconciliation/runs}: the run that was started in the background. */
public record ReconciliationStarted(UUID runId) {}
