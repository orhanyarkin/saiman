package io.github.orhanyarkin.saiman.ledger.journal;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.Locale;

/**
 * Account codes of ADR-0017. Wallet accounts record flows, not faucet balances; there is no facilitator account
 * because {@code exact} transfers go from the buyer to {@code payTo} directly. Addresses are lower-cased here.
 */
public final class ChartOfAccounts {

    public static final String USDC = "USDC";
    private static final int USDC_DECIMALS = 6;

    private ChartOfAccounts() {}

    public static Account buyerAvailable(String payer) {
        return buyer(payer, "wallet:available", AccountType.ASSET);
    }

    public static Account buyerEncumbered(String payer) {
        return buyer(payer, "wallet:encumbered", AccountType.ASSET);
    }

    public static Account buyerExpense(String payer) {
        return buyer(payer, "expense:data", AccountType.EXPENSE);
    }

    public static Account sellerWallet(String payTo) {
        return seller(payTo, "wallet", AccountType.ASSET);
    }

    public static Account sellerRevenue(String payTo) {
        return seller(payTo, "revenue:data", AccountType.REVENUE);
    }

    /** Contra-revenue: sales the seller credited back because it did not serve them (ADR-0021). */
    public static Account sellerCreditNotes(String payTo) {
        return seller(payTo, "revenue:credit-notes", AccountType.REVENUE);
    }

    /** What the seller owes buyers for credit notes until they are redeemed (out of scope) or reversed. */
    public static Account sellerCustomerCredits(String payTo) {
        return seller(payTo, "liability:customer-credits", AccountType.LIABILITY);
    }

    /** {@code buyer:<payer>:suspense:usdc}: where reconciliation parks a buyer-wallet difference (ADR-0029). */
    public static Account buyerSuspense(String payer) {
        return buyer(payer, "suspense:" + USDC.toLowerCase(Locale.ROOT), AccountType.SUSPENSE);
    }

    /** {@code seller:<payTo>:suspense:usdc}: where reconciliation parks a seller-wallet difference (ADR-0029). */
    public static Account sellerSuspense(String payTo) {
        return seller(payTo, "suspense:" + USDC.toLowerCase(Locale.ROOT), AccountType.SUSPENSE);
    }

    /**
     * The suspense account of {@code book} for this amount: {@code buyer:<wallet>:suspense:usdc} or
     * {@code seller:<wallet>:suspense:usdc}. An entry belongs to one book (ADR-0029), so a difference is parked in
     * the book whose wallet moved.
     *
     * @throws IllegalArgumentException for {@link LedgerBook#PLATFORM}, which takes no new postings
     */
    public static Account suspenseOf(LedgerBook book, String wallet) {
        return switch (book) {
            case BUYER -> buyerSuspense(wallet);
            case SELLER -> sellerSuspense(wallet);
            case PLATFORM -> throw new IllegalArgumentException("platform:suspense:usdc is retired (ADR-0029)");
        };
    }

    /**
     * {@code platform:suspense:usdc}: where reconciliation parked differences until ADR-0029 moved suspense into the
     * book whose wallet moved. Retired — it keeps its history (V9 reverses the cross-book entries, so it nets to
     * zero) and takes no new postings; kept for reads and for tests about that history.
     */
    public static Account suspense(Money of) {
        return new Account(
                "platform:suspense:" + of.asset().toLowerCase(Locale.ROOT),
                LedgerBook.PLATFORM,
                AccountType.SUSPENSE,
                of.asset(),
                of.decimals(),
                null);
    }

    private static Account buyer(String payer, String suffix, AccountType type) {
        String b = payer.toLowerCase(Locale.ROOT);
        return new Account("buyer:" + b + ":" + suffix, LedgerBook.BUYER, type, USDC, USDC_DECIMALS, b);
    }

    private static Account seller(String payTo, String suffix, AccountType type) {
        String s = payTo.toLowerCase(Locale.ROOT);
        return new Account("seller:" + s + ":" + suffix, LedgerBook.SELLER, type, USDC, USDC_DECIMALS, s);
    }
}
