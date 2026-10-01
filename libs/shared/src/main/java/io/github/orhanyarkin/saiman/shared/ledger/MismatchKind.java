package io.github.orhanyarkin.saiman.shared.ledger;

/** What the reconciliation found to differ between the books and Base Sepolia (ADR-0018). */
public enum MismatchKind {
    AMOUNT_MISMATCH,
    PARTY_MISMATCH,
    TX_NOT_FOUND,
    TX_FAILED,
    TX_NOT_FOR_AUTHORIZATION,
    SETTLED_BUT_UNUSED,
    UNUSED_BUT_SETTLED,
    CONFLICTING_TX,
    ENCUMBRANCE_NOT_CLEARED,
    /** The chain is final past validBefore + grace but the books never reached a terminal state. */
    BOOKS_OPEN,
    /** A fact contradicted an earlier fact for the same authorization (amount, payTo or validBefore). */
    CONFLICTING_FACT
}
