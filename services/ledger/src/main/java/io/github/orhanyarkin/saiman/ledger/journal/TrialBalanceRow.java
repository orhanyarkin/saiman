package io.github.orhanyarkin.saiman.ledger.journal;

import java.math.BigInteger;

/**
 * One account's totals in one asset, in atomic units (integers on the wire, CLAUDE.md rule 4). Totals are
 * {@link BigInteger}: every posting is at most 2^53-1, but a sum over many postings is not bounded by a {@code long}
 * (a flood of forged events must not turn the trial balance into a 500). Jackson writes them as JSON integers.
 *
 * @param balance {@code debit - credit}; negative for accounts with a credit balance (revenue, available)
 */
public record TrialBalanceRow(
        String account,
        String book,
        String type,
        String asset,
        int decimals,
        BigInteger debit,
        BigInteger credit,
        BigInteger balance) {}
