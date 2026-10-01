package io.github.orhanyarkin.saiman.eventing;

/**
 * Consumer-side dedupe (CLAUDE.md rule 5). Must run inside the consumer's transaction
 * ({@code Propagation.MANDATORY}): {@code INSERT INTO inbox ... ON CONFLICT DO NOTHING}.
 */
public interface InboxGuard {

    /** @return true the first time this consumer sees {@code eventId}; false for a redelivery */
    boolean firstDelivery(String eventId, String consumer, String topic);
}
