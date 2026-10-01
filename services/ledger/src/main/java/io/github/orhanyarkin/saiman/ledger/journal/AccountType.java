package io.github.orhanyarkin.saiman.ledger.journal;

/** Classification of an account; a balance is always {@code debits - credits} regardless of type. */
public enum AccountType {
    ASSET,
    LIABILITY,
    REVENUE,
    EXPENSE,
    SUSPENSE
}
