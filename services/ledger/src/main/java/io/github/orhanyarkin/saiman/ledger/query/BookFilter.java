package io.github.orhanyarkin.saiman.ledger.query;

/** Which book a listed payment must have entries in. PLATFORM is not a filter: it only holds reconciliation fixes. */
public enum BookFilter {
    BUYER,
    SELLER
}
