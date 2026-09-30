package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A read-only snapshot of a {@code payment_intent} row, without the idempotency key, nonce or
 * payer (none of which may leave the spend-control plane).
 *
 * @param amountAtomic the seller's offered amount, set when the intent reached the spend guard
 * @param reservedDay the UTC day the reservation is counted on, set while reserved
 */
public record PaymentIntentView(
        UUID id,
        UUID runId,
        String tool,
        String argsHash,
        String resource,
        PaymentIntentStatus status,
        @Nullable Long amountAtomic,
        @Nullable String payTo,
        @Nullable String network,
        @Nullable String asset,
        @Nullable LocalDate reservedDay,
        @Nullable String txHash,
        @Nullable DenyReason denyReason) {}
