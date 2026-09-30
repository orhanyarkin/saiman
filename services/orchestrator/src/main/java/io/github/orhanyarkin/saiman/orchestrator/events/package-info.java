/**
 * The run event log ({@code agent.run-step.v1}, ADR-0014): {@link
 * io.github.orhanyarkin.saiman.orchestrator.events.RunEventAppender} appends to the {@code run_event}
 * table with a gap-free per-run {@code seq}, {@link
 * io.github.orhanyarkin.saiman.orchestrator.events.RunEventBus} fans committed events out in memory,
 * and the SSE endpoint replays from the database and then tails the bus. No payload carries an
 * idempotency key, nonce or signature.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.events;

import org.jspecify.annotations.NullMarked;
