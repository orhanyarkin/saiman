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
    /** Ids reach database text columns: a bounded charset keeps one forged record from failing a consumer forever. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9:_-]{0,64}");

    public EventMetadata {
        if (!ID.matcher(eventId).matches()) {
            throw new IllegalArgumentException("eventId must be 1-64 letters, digits or hyphens");
        }
        if (!PRODUCER.matcher(producer).matches()) {
            throw new IllegalArgumentException("producer must be a lower-case service name");
        }
        if (!CORRELATION.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("correlationId must be at most 64 letters, digits, ':', '_' or '-'");
        }
    }
}
