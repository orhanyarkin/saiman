package io.github.orhanyarkin.saiman.ledger.journal;

/** Journal entry kinds (ADR-0017). */
public enum EntryKind {
    /** Buyer signed an authorization: available to encumbered. */
    ENCUMBER,
    /** Buyer's payment settled: encumbered to expense. */
    SETTLE,
    /** Authorization expired unused: encumbered back to available. */
    RELEASE,
    /** Seller received a payment: revenue into the wallet. */
    SALE,
    /** Reserved for settle-first refunds (M4b). */
    CREDIT_NOTE,
    /** Reconciliation moved a difference to suspense. */
    ADJUSTMENT,
    /** Mirrors an earlier entry with the sides swapped. */
    REVERSAL,
    /** Optional USD book for LLM costs (not posted in M4). */
    LLM_USAGE;

    /** Kinds posted at most once per payment and book (the partial unique index in V1__ledger.sql). */
    public boolean oncePerPayment() {
        return switch (this) {
            case ENCUMBER, SETTLE, RELEASE, SALE, CREDIT_NOTE -> true;
            case ADJUSTMENT, REVERSAL, LLM_USAGE -> false;
        };
    }
}
