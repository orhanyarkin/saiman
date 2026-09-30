/**
 * The spend-control plane (ADR-0013): per-run budgets, the global daily cap, the payee allowlist and
 * the approval threshold, decided by deterministic code under Postgres row locks, never by a prompt.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.budget;

import org.jspecify.annotations.NullMarked;
