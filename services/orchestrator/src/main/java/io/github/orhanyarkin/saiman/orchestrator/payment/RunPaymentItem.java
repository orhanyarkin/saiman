package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The current state of one payment intent of a run, for {@code GET /api/v1/runs/{id}/payments}.
 * Deliberately a different shape from {@link PaymentIntentView}: it adds the timestamps and leaves
 * out everything internal. There is no idempotency key, nonce, signature or payer address here, and
 * none of them may be added (they never leave the spend-control plane).
 *
 * @param amount the seller's offered amount; null until the intent reached the spend guard
 * @param payTo the payee from the seller's offer, if known
 * @param txHash the settlement transaction, once known
 */
public record RunPaymentItem(
        UUID paymentIntentId,
        String tool,
        PaymentIntentStatus status,
        @Nullable Money amount,
        @Nullable String payTo,
        String resource,
        @Nullable String txHash,
        Instant createdAt,
        Instant updatedAt) {}
