package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.util.List;

/** Body of {@code GET /api/v1/runs/{id}/payments}: the run's intents, oldest first. */
public record RunPayments(List<RunPaymentItem> items) {}
