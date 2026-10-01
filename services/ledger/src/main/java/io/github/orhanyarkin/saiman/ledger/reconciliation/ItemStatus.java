package io.github.orhanyarkin.saiman.ledger.reconciliation;

/** The outcome of checking one payment in a reconciliation run, as the report shows it. */
public enum ItemStatus {
    /** The books agree with the chain (possibly after an earlier adjustment). */
    MATCHED,
    /** Not decidable yet: receipt above the safe block, authorization still valid, or the RPC was unavailable. */
    PENDING,
    /** At least one {@link io.github.orhanyarkin.saiman.shared.ledger.MismatchKind} applies. */
    MISMATCH,
    /** The authorization is used on chain but no transaction was found for it: information, not a mismatch. */
    TX_UNKNOWN
}
