package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A successful seller response. The body is untrusted tool output (ADR-0014: sanitise before a
 * model sees it).
 *
 * @param amount what was paid (zero if the seller did not ask for payment)
 * @param txHash the settlement transaction, if paid
 */
public record PaidResponse(
        UUID paymentIntentId,
        int status,
        String body,
        Money amount,
        @Nullable String txHash) {

    public boolean paid() {
        return txHash != null;
    }
}
