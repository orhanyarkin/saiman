package io.github.orhanyarkin.saiman.shared.run;

import java.time.Instant;
import java.util.UUID;

/**
 * One step event of a run. {@code seq} starts at 1 and is gap-free per run; the SSE {@code id} is the
 * seq, so a client resumes with {@code Last-Event-ID}. The event id is {@code <runId>:<seq>}, which is
 * what a future consumer dedupes on (M4 relays the log through the outbox).
 *
 * @param data the payload; its type is the one {@link RunEventData} documents for {@code type}
 */
public record RunEvent(UUID runId, int seq, RunEventType type, Instant occurredAt, RunEventData data) {

    /** Schema name, also the future topic ({@code agent.run-step.v1}). */
    public static final String SCHEMA = "agent.run-step.v1";

    public RunEvent {
        if (seq < 1) {
            throw new IllegalArgumentException("seq starts at 1");
        }
    }

    public String eventId() {
        return runId + ":" + seq;
    }
}
