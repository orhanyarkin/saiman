package io.github.orhanyarkin.saiman.ledger.journal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An immutable, balanced journal entry about to be posted. The constructor is the domain half of the
 * balanced-postings invariant (the database's deferred constraint trigger is the other half, ADR-0017): at least
 * two postings and, per asset, debits equal credits.
 *
 * @param paymentId the ledger's payment id, or null for entries not about one payment
 * @param paymentKey the payment's business key, or null
 * @param sourceEventId the integration event that caused the entry, or null (reconciliation)
 * @param reversesEntryId set for {@link EntryKind#REVERSAL} only
 */
public record JournalEntry(
        UUID id,
        @Nullable UUID paymentId,
        @Nullable String paymentKey,
        LedgerBook book,
        EntryKind kind,
        @Nullable String sourceEventId,
        @Nullable UUID reversesEntryId,
        String description,
        Instant effectiveAt,
        List<Posting> postings) {

    public JournalEntry {
        postings = List.copyOf(postings);
        if ((kind == EntryKind.REVERSAL) != (reversesEntryId != null)) {
            throw new IllegalArgumentException("exactly the REVERSAL entries name the entry they reverse");
        }
        if (description.isBlank() || description.length() > 256) {
            throw new IllegalArgumentException("description must be 1-256 characters");
        }
        requireBalanced(postings);
        requireSingleBook(book, postings);
    }

    /**
     * Checks that the entry belongs to exactly one book (ADR-0029): every posting's account is in {@code book}, so
     * summing a balanced entry into its book keeps that book balanced. The database's condition in
     * {@code check_entry_balanced} (V10__per_book_balance.sql) is the other half.
     *
     * @throws CrossBookEntryException if some posting's account belongs to another book
     */
    public static void requireSingleBook(LedgerBook book, List<Posting> postings) {
        for (Posting posting : postings) {
            LedgerBook of = posting.account().book();
            if (of != book) {
                throw new CrossBookEntryException("posting on "
                        + posting.account().code() + " is in book " + of + ", but the entry is in book " + book);
            }
        }
    }

    /**
     * Checks the balanced-postings invariant.
     *
     * @throws UnbalancedEntryException if there are fewer than two postings or debits differ from credits for
     *     some asset
     */
    public static void requireBalanced(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new UnbalancedEntryException("a journal entry needs at least two postings");
        }
        Map<String, Long> net = new LinkedHashMap<>();
        for (Posting posting : postings) {
            String asset = posting.amount().asset() + "/" + posting.amount().decimals();
            net.merge(asset, posting.signedAtomic(), Math::addExact);
        }
        net.forEach((asset, sum) -> {
            if (sum != 0) {
                throw new UnbalancedEntryException(
                        "debits differ from credits by " + sum + " atomic units of " + asset);
            }
        });
    }
}
