package io.github.orhanyarkin.saiman.ledger.journal;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One account of the chart (ADR-0017). Accounts are created on first use.
 *
 * @param code e.g. {@code buyer:0xabc...:wallet:available}; addresses are lower-case
 * @param wallet the lower-case address whose flows the account records, or null (suspense)
 */
public record Account(
        String code,
        LedgerBook book,
        AccountType type,
        String asset,
        int decimals,
        @Nullable String wallet) {

    private static final Pattern WALLET = Pattern.compile("0x[0-9a-f]{40}");

    public Account {
        if (code.isBlank() || code.length() > 128) {
            throw new IllegalArgumentException("account code must be 1-128 characters");
        }
        if (wallet != null && !WALLET.matcher(wallet).matches()) {
            throw new IllegalArgumentException("wallet must be a lower-case 0x address");
        }
    }
}
