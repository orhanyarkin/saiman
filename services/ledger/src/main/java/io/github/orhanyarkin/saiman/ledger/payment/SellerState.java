package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import java.util.Set;

/**
 * The seller's book for one authorization, ordered like {@link BuyerState} (declaration order is the rank): a
 * failed settlement can be followed, or preceded, by a successful one, and the successful one wins. {@code
 * CREDITED} (ADR-0021) is a settled sale the seller then did not serve: it implies the sale too, so a credit note
 * delivered before its {@code settled} posts both entries and the late {@code settled} posts nothing.
 */
public enum SellerState {
    NONE,
    SETTLE_FAILED,
    SETTLED,
    CREDITED;

    /** The one-per-payment entries that have been posted once the seller's book is in this state. */
    public Set<EntryKind> impliedEntries() {
        return switch (this) {
            case NONE, SETTLE_FAILED -> Set.of();
            case SETTLED -> Set.of(EntryKind.SALE);
            case CREDITED -> Set.of(EntryKind.SALE, EntryKind.CREDIT_NOTE);
        };
    }

    public SellerState join(SellerState other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
