package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A signed intent's authorization, as the spend-control plane needs it to publish payment events and resolve a
 * HELD intent on chain. Unlike {@link PaymentIntentView} it carries the payer and nonce (public on chain once
 * used, allowed in {@code payments.*} events) but still never the idempotency key or the signature. Internal:
 * never logged, never returned over HTTP.
 *
 * @param validBefore unix seconds; the authorization can be used until then
 * @param reservedDay the UTC day whose counters hold the reservation
 * @param txHash set once settled by the facilitator (or found on chain)
 */
public record IntentAuthorization(
        UUID id,
        UUID runId,
        PaymentIntentStatus status,
        String resource,
        String network,
        String asset,
        String payTo,
        long amountAtomic,
        String payer,
        String nonce,
        long validBefore,
        LocalDate reservedDay,
        @Nullable String txHash) {

    @Override
    public String toString() {
        // No payer or nonce in a log line by accident.
        return "IntentAuthorization[id=" + id + ", status=" + status + "]";
    }
}
