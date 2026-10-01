package io.github.orhanyarkin.saiman.ledger.journal;

/**
 * One account's totals in one asset, in atomic units (integers on the wire, CLAUDE.md rule 4).
 *
 * @param balance {@code debit - credit}; negative for accounts with a credit balance (revenue, available)
 */
public record TrialBalanceRow(
        String account, String book, String type, String asset, int decimals, long debit, long credit, long balance) {}
