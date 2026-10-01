package io.github.orhanyarkin.saiman.ledger.journal;

import io.github.orhanyarkin.saiman.shared.payments.Book;

/** Whose books an account or entry belongs to: the buyer, the seller, or the platform (suspense). */
public enum LedgerBook {
    BUYER,
    SELLER,
    PLATFORM;

    public static LedgerBook of(Book book) {
        return switch (book) {
            case BUYER -> BUYER;
            case SELLER -> SELLER;
        };
    }
}
