package io.github.orhanyarkin.saiman.shared.payments;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code payments.settled.v1}: the authorization was used. The buyer (orchestrator) and the seller (seller-api)
 * each report it for their own {@link Book}; the transaction hash is absent only when the buyer resolved a HELD
 * intent from {@code authorizationState} without finding the transaction ({@link SettlementEvidence#CHAIN}).
 */
public record PaymentSettled(
        EventMetadata meta,
        AuthorizationRef authorization,
        Money amount,
        String payTo,
        String resource,
        Book book,
        @Nullable String txHash,
        SettlementEvidence evidence,
        @Nullable UUID paymentIntentId,
        @Nullable UUID runId) {

    public PaymentSettled {
        PaymentEvents.requireCommon(amount, payTo, resource);
        if (txHash != null && !PaymentEvents.TX_HASH.matcher(txHash).matches()) {
            throw new IllegalArgumentException("txHash must be 32 bytes of hex");
        }
        if (txHash == null && evidence != SettlementEvidence.CHAIN) {
            throw new IllegalArgumentException("only chain evidence may lack a transaction hash");
        }
    }
}
