package io.github.orhanyarkin.saiman.ledger.query;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One payment as the dashboard lists it: the projection's three book states, without the payment key or nonce.
 *
 * @param runId the agent run that paid, if the buyer's events named one
 * @param buyerState NONE, AUTHORIZED, SETTLED or RELEASED
 * @param sellerState NONE, SETTLE_FAILED, SETTLED or CREDITED
 * @param chainState UNKNOWN, USED or UNUSED (what reconciliation last read on Base Sepolia)
 * @param payTo the seller's address, lower-case
 */
public record PaymentSummary(
        UUID paymentId,
        @Nullable UUID runId,
        String buyerState,
        String sellerState,
        String chainState,
        Money amount,
        String payTo,
        Instant createdAt,
        Instant updatedAt,
        @Nullable String buyerTxHash,
        @Nullable String sellerTxHash,
        @Nullable String chainTxHash) {}
