package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import java.util.Set;

/**
 * The seller's book for one authorization, ordered like {@link BuyerState} (declaration order is the rank): a
 * failed settlement can be followed, or preceded, by a successful one, and the successful one wins.
 */
public enum SellerState {
    NONE,
    SETTLE_FAILED,
    SETTLED;

    /** The one-per-payment entries that have been posted once the seller's book is in this state. */
    public Set<EntryKind> impliedEntries() {
        return this == SETTLED ? Set.of(EntryKind.SALE) : Set.of();
    }

    public SellerState join(SellerState other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
