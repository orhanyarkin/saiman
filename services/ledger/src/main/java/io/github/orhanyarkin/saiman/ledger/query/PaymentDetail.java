package io.github.orhanyarkin.saiman.ledger.query;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A payment's drill-down: its projection, every journal entry about it (buyer, seller and platform books, in posting
 * order) and what reconciliation found.
 */
public record PaymentDetail(PaymentSummary payment, List<Entry> entries, List<Finding> mismatches) {

    public PaymentDetail {
        entries = List.copyOf(entries);
        mismatches = List.copyOf(mismatches);
    }

    /**
     * One journal entry.
     *
     * @param book BUYER, SELLER or PLATFORM
     * @param kind ENCUMBER, SETTLE, RELEASE, SALE, CREDIT_NOTE, ADJUSTMENT or REVERSAL
     * @param reversesEntryId set for REVERSAL entries only
     */
    public record Entry(
            UUID entryId,
            String book,
            String kind,
            String description,
            Instant effectiveAt,
            @Nullable UUID reversesEntryId,
            List<Line> postings) {

        public Entry {
            postings = List.copyOf(postings);
        }
    }

    /**
     * One posting of an entry; the amount is never negative, the side carries the direction.
     *
     * @param side DEBIT or CREDIT
     */
    public record Line(String accountCode, String side, Money amount) {}

    /**
     * A reconciliation finding about this payment (table {@code reconciliation_mismatch}).
     *
     * @param status ADJUSTED when an ADJUSTMENT entry moved the difference to suspense, REPORTED when nothing was
     *     posted (the finding is information for a human)
     * @param reconciliationRunId the run that found it; null for findings of the event consumer (CONFLICTING_FACT)
     * @param ledgerValue what the books said, if the finding is about an amount
     * @param chainValue what the chain said, if the finding is about an amount
     */
    public record Finding(
            String kind,
            String status,
            @Nullable UUID reconciliationRunId,
            Instant detectedAt,
            @Nullable UUID adjustmentEntryId,
            @Nullable Money ledgerValue,
            @Nullable Money chainValue) {}
}
