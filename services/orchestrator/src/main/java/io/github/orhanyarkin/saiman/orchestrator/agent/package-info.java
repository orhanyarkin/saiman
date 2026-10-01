/**
 * The research agents (ADR-0014): a fixed planner -> researcher -> risk -> synthesis pipeline on
 * Spring AI {@code ChatClient}s handed out by the model router. Every model answer and every tool
 * result is untrusted data: plans are validated against the seller's ticker catalogue, tools take
 * only a ticker and a question, and citations are rebuilt from the evidence the run retrieved.
 * Limits (budgets, call counts, payees) live in code outside this package.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.agent;

import org.jspecify.annotations.NullMarked;
