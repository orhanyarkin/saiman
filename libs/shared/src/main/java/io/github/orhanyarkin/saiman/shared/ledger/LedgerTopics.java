package io.github.orhanyarkin.saiman.shared.ledger;

/** Topic names, {@code <domain>.<event>.v1}. */
public final class LedgerTopics {

    public static final String ENTRY_POSTED = "ledger.entry-posted.v1";
    public static final String RECONCILIATION_MISMATCH = "ledger.reconciliation-mismatch.v1";

    private LedgerTopics() {}
}
