package io.github.orhanyarkin.saiman.shared.events;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Identity of one integration event. Consumers dedupe on {@code eventId} through their inbox table in
 * the same transaction as their state change (CLAUDE.md rule 5).
 *
 * @param eventId globally unique, stable across redeliveries (a UUID string)
 * @param occurredAt when the state change committed at the producer
 * @param producer the producing service ({@code orchestrator}, {@code seller-api}, {@code ledger})
 * @param correlationId a run id or request id; never a secret, nonce or idempotency key
 */
public record EventMetadata(String eventId, Instant occurredAt, String producer, String correlationId) {

    private static final Pattern PRODUCER = Pattern.compile("[a-z][a-z-]{1,31}");

    public EventMetadata {
        if (eventId.isBlank() || eventId.length() > 64) {
            throw new IllegalArgumentException("eventId must be 1-64 characters");
        }
        if (!PRODUCER.matcher(producer).matches()) {
            throw new IllegalArgumentException("producer must be a lower-case service name");
        }
        if (correlationId.length() > 64) {
            throw new IllegalArgumentException("correlationId must be at most 64 characters");
        }
    }
}
