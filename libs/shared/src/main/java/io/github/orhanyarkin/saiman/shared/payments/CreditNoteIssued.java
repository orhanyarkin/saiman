package io.github.orhanyarkin.saiman.shared.payments;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;

/**
 * {@code payments.credit-note-issued.v1}: the seller settled a payment up front (x402 {@code upfront} flow,
 * ADR-0021) and then did not serve the request, so it owes the buyer the full amount. Always the seller's
 * {@link Book}; the settlement transaction is known, so {@code txHash} is required.
 *
 * @param httpStatus the status the buyer received, 300-599 (500 when the handler threw)
 * @param reasonCode a bounded code ({@code [a-z0-9_]{1,64}}), e.g. {@code handler_client_error}, never free text
 */
public record CreditNoteIssued(
        EventMetadata meta,
        AuthorizationRef authorization,
        Money amount,
        String payTo,
        String resource,
        Book book,
        String txHash,
        int httpStatus,
        String reasonCode) {

    public CreditNoteIssued {
        PaymentEvents.requireCommon(amount, payTo, resource);
        if (book != Book.SELLER) {
            throw new IllegalArgumentException("only the seller issues credit notes");
        }
        if (!PaymentEvents.TX_HASH.matcher(txHash).matches()) {
            throw new IllegalArgumentException("txHash must be 32 bytes of hex");
        }
        if (httpStatus < 300 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be 300-599");
        }
        if (!PaymentEvents.REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode must match [a-z0-9_]{1,64}");
        }
    }
}
