package io.github.orhanyarkin.saiman.ledger.reconciliation;

import java.util.Optional;

/**
 * Asks seller-api whether it really issued a credit note (ADR-0021). Kafka is unauthenticated until M6, so a {@code
 * CreditNoteIssued} is only a claim; the seller's own {@code credit_note} row is the corroboration. Implementations
 * must never turn "could not ask" into "no credit note": that would be a false finding.
 */
public interface SellerCreditNoteClient {

    /** What the seller recorded; the tx hash is lower-case. */
    record SellerCreditNote(String txHash, long amountAtomic) {}

    /** The seller could not give a definite answer (unreachable, 5xx, breaker open, malformed answer). */
    class SellerUnavailableException extends RuntimeException {
        public SellerUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Looks up the seller's credit note for one payment.
     *
     * @param paymentKey the ledger's payment key ({@code network:asset:payer:nonce}, lower-case)
     * @return the seller's credit note, or empty when the seller definitely has none
     * @throws SellerUnavailableException when there is no definite answer
     */
    Optional<SellerCreditNote> find(String paymentKey);
}
