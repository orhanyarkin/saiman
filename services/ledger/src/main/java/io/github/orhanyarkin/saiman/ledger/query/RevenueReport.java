package io.github.orhanyarkin.saiman.ledger.query;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Seller revenue per {@code payTo} from the SELLER book (ADR-0021), largest gross sales first.
 */
public record RevenueReport(List<Seller> items) {

    public RevenueReport {
        items = List.copyOf(items);
    }

    /**
     * One seller's totals. Sums run as {@code numeric} in Postgres and saturate at {@code Long.MAX_VALUE} (a flood of
     * forged events must not turn the report into a 500); a client that refuses integers above 2^53-1 shows such a
     * value as out of range rather than a wrong number.
     *
     * @param payTo the seller's address, lower-case
     * @param grossSales credits of {@code revenue:data} (one SALE per settled request)
     * @param creditNotes debits of {@code revenue:credit-notes} (one CREDIT_NOTE per paid request the seller did not
     *     serve)
     * @param netRevenue {@code grossSales - creditNotes}; signed by type, though the books never let it go negative
     * @param customerCredits credits of {@code liability:customer-credits}: what the seller owes buyers
     * @param sales number of SALE entries
     * @param credited number of CREDIT_NOTE entries
     */
    @Schema(name = "SellerRevenue")
    public record Seller(
            String payTo,
            Money grossSales,
            Money creditNotes,
            SignedAmount netRevenue,
            Money customerCredits,
            long sales,
            long credited) {}
}
