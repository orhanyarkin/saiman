package io.github.orhanyarkin.saiman.ledger.query;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One keyset page of payments, newest first.
 *
 * @param nextCursor opaque; pass it as {@code before} for the next page. Null on the last page.
 */
public record PaymentPage(
        List<PaymentSummary> items, @Nullable String nextCursor) {

    public PaymentPage {
        items = List.copyOf(items);
    }
}
