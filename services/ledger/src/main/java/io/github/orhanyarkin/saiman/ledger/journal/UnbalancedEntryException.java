package io.github.orhanyarkin.saiman.ledger.journal;

/** A journal entry with fewer than two postings, or with debits different from credits for some asset. */
public class UnbalancedEntryException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
