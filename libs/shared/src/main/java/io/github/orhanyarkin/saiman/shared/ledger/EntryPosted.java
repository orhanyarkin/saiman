package io.github.orhanyarkin.saiman.shared.ledger;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code ledger.entry-posted.v1}: one immutable journal entry. Debits equal credits per asset (the ledger's
 * database enforces it; this record checks it again).
 *
 * @param kind ENCUMBER, SETTLE, RELEASE, SALE, CREDIT_NOTE, ADJUSTMENT, REVERSAL or LLM_USAGE
 */
public record EntryPosted(
        EventMetadata meta,
        UUID entryId,
        UUID paymentId,
        String kind,
        List<PostingLine> postings,
        Instant effectiveAt) {

    public EntryPosted {
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("an entry has at least two postings");
        }
        Map<String, Long> net = new HashMap<>();
        for (PostingLine line : postings) {
            long signed = line.side() == Side.DEBIT
                    ? line.amount().atomicUnits()
                    : -line.amount().atomicUnits();
            net.merge(line.amount().asset() + "/" + line.amount().decimals(), signed, Math::addExact);
        }
        if (net.values().stream().anyMatch(v -> v != 0)) {
            throw new IllegalArgumentException("debits must equal credits per asset");
        }
    }
}
