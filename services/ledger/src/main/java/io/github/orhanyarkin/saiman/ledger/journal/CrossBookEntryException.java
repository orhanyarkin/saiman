package io.github.orhanyarkin.saiman.ledger.journal;

/**
 * A journal entry whose postings do not all belong to the entry's book. An entry belongs to exactly one book
 * (ADR-0029), so that per-book balance follows from per-entry balance.
 */
public class CrossBookEntryException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public CrossBookEntryException(String message) {
        super(message);
    }
}
