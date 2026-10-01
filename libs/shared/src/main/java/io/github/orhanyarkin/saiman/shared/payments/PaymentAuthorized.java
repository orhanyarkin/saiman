package io.github.orhanyarkin.saiman.shared.payments;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.UUID;

/**
 * {@code payments.authorized.v1}: the buyer signed an authorization and is about to send it. Money may move
 * from now until {@code validBefore}. Produced by the orchestrator when an intent becomes SIGNED.
 */
public record PaymentAuthorized(
        EventMetadata meta,
        AuthorizationRef authorization,
        Money amount,
        String payTo,
        String resource,
        UUID paymentIntentId,
        UUID runId) {

    public PaymentAuthorized {
        PaymentEvents.requireCommon(amount, payTo, resource);
    }
}
