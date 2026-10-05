package io.github.orhanyarkin.saiman.ledger.query;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Seller revenue per {@code payTo} and asset from the SELLER book (ADR-0021), largest gross sales first, at most
 * {@link LedgerQueries#MAX_REVENUE_ROWS} rows.
 *
 * <p>Kafka is unauthenticated until M6, so the books can hold forged seller facts (a phantom SALE, a phantom
 * CREDIT_NOTE) that reconciliation flags but never removes from the revenue accounts: its ADJUSTMENT moves only the
 * seller wallet against {@code platform:suspense}. The top-level figures of each row are therefore <b>per books, not
 * chain-verified</b>; {@link Seller#chainVerified()} is the part Base Sepolia confirms.
 *
 * @param items one row per seller and asset
 * @param truncated true when more sellers exist than the {@link LedgerQueries#MAX_REVENUE_ROWS} rows shown (payTo
 *     values can be forged, so the list is capped)
 */
@Schema(
        description = "Revenue per seller and asset, largest gross first, at most 100 rows; truncated says more exist"
                + " (filter with payTo).")
public record RevenueReport(List<Seller> items, boolean truncated) {

    public RevenueReport {
        items = List.copyOf(items);
    }

    /**
     * One seller's totals. Every account figure is a net over all its postings (REVERSAL entries included), summed as
     * {@code numeric} in Postgres and clamped to [0, 2^53-1] ({@code netRevenue}: [-(2^53-1), 2^53-1]), the range
     * every JSON client reads exactly; {@code saturated} says a clamp happened.
     *
     * @param payTo the seller's address, lower-case
     * @param grossSales per books, not chain-verified: {@code revenue:data} credits minus debits
     * @param creditNotes per books, not chain-verified: {@code revenue:credit-notes} debits minus credits
     * @param netRevenue per books, not chain-verified: {@code grossSales - creditNotes}; signed by type
     * @param customerCredits per books: {@code liability:customer-credits} credits minus debits (what the seller owes
     *     buyers; a human REVERSAL of a credit note lowers it)
     * @param sales number of SALE entries
     * @param credited number of CREDIT_NOTE entries
     * @param chainVerified the same figures from verified payments only
     * @param unverifiedGrossSales {@code grossSales - chainVerified.grossSales}, never negative: sales Base Sepolia has
     *     not confirmed (yet, or ever)
     * @param openFindings number of this seller's payments with at least one unresolved reconciliation finding (one
     *     without an adjustment entry; a finding reconciliation already moved to suspense is not counted)
     * @param saturated true when any figure in this row was clamped
     */
    @Schema(
            name = "SellerRevenue",
            description = "One seller's revenue. grossSales, creditNotes, netRevenue and customerCredits are PER BOOKS,"
                    + " NOT CHAIN-VERIFIED: forged seller events (unauthenticated Kafka until M6) stay in them even"
                    + " after reconciliation flags them. chainVerified is the part Base Sepolia confirms;"
                    + " unverifiedGrossSales = grossSales - chainVerified.grossSales (never negative); openFindings"
                    + " counts this seller's payments with an unresolved reconciliation finding (one reconciliation"
                    + " did not adjust; adjusted findings are not counted). Amounts are clamped to"
                    + " [0, 2^53-1] (netRevenue: +-(2^53-1)) and saturated says a clamp happened.")
    public record Seller(
            String payTo,
            Money grossSales,
            Money creditNotes,
            SignedAmount netRevenue,
            Money customerCredits,
            long sales,
            long credited,
            Verified chainVerified,
            Money unverifiedGrossSales,
            long openFindings,
            boolean saturated) {}

    /**
     * Revenue Base Sepolia confirms. A sale counts when reconciliation matched its canonical receipt on chain
     * ({@code chainState} USED with a {@code chainTxHash}; a used authorization whose transaction was never found does
     * not count) and recorded no finding against the payment other than buyer-side or bookkeeping ones
     * ({@code ENCUMBRANCE_NOT_CLEARED}, {@code BOOKS_OPEN}) or an uncorroborated credit note. A credit note counts
     * when its sale counts and seller-api corroborated it with the ledger's tx hash and amount (ADR-0021).
     *
     * @param grossSales {@code revenue:data} net over verified sales
     * @param creditNotes {@code revenue:credit-notes} net over corroborated credit notes of verified sales
     * @param netRevenue {@code grossSales - creditNotes}
     */
    @Schema(
            name = "ChainVerifiedRevenue",
            description = "Revenue Base Sepolia confirms: sales whose canonical on-chain receipt was matched"
                    + " (chainState USED with a chainTxHash) without a chain finding, and credit notes of those sales"
                    + " that seller-api corroborated with the same tx hash and amount.")
    public record Verified(Money grossSales, Money creditNotes, SignedAmount netRevenue) {}
}
