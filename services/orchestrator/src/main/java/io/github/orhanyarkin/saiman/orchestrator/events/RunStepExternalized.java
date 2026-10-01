package io.github.orhanyarkin.saiman.orchestrator.events;

import java.util.UUID;

/**
 * Published by {@link RunEventAppender} in the transaction that appends a {@code run_event} row, for the
 * outbox: Kafka's {@code agent.run-step.v1} value is {@code envelope}, the same JSON the SSE stream and the
 * export endpoint send (docs/events/agent.run-step.v1.md), keyed by the run id.
 *
 * @param envelope the encoded envelope ({@link RunEventCodec#encodeEnvelope})
 */
public record RunStepExternalized(UUID runId, String envelope) {}
