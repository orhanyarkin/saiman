package io.github.orhanyarkin.saiman.sellerapi.settlement;

import java.time.Instant;

/**
 * One credit note as {@code GET /internal/credit-notes/{paymentKey}} returns it: just enough for the ledger to
 * corroborate a {@code CreditNoteIssued} it consumed (ADR-0021). No payer, payTo or resource.
 *
 * @param paymentKey {@code network:asset:payer:nonce}, lower-case
 * @param txHash the settle transaction the seller credited
 * @param amountAtomic the credited amount in USDC atomic units (6 decimals)
 * @param httpStatus the status the buyer got
 * @param reasonCode why the request was not served
 * @param createdAt when the seller recorded the credit note
 */
record CreditNoteView(
        String paymentKey, String txHash, long amountAtomic, int httpStatus, String reasonCode, Instant createdAt) {}
