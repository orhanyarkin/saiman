package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import java.util.Set;

/**
 * The buyer's book for one authorization, as a join-semilattice: facts only move it up ({@link #join}), so the
 * result does not depend on delivery order. {@code SETTLED} dominates {@code RELEASED}: the chain decides, and
 * "used" cannot be undone. Declaration order is the rank.
 */
public enum BuyerState {
    NONE,
    AUTHORIZED,
    RELEASED,
    SETTLED;

    /** The one-per-payment entries that have been posted once the buyer's book is in this state. */
    public Set<EntryKind> impliedEntries() {
        return switch (this) {
            case NONE -> Set.of();
            case AUTHORIZED -> Set.of(EntryKind.ENCUMBER);
            case RELEASED -> Set.of(EntryKind.ENCUMBER, EntryKind.RELEASE);
            case SETTLED -> Set.of(EntryKind.ENCUMBER, EntryKind.SETTLE);
        };
    }

    public BuyerState join(BuyerState other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
