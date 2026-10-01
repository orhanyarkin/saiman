package io.github.orhanyarkin.saiman.shared.payments;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code payments.failed.v1}: the producer did not settle. {@link Finality#FINAL} comes from the buyer after
 * reading {@code authorizationState == false} past {@code validBefore}; {@link Finality#AMBIGUOUS} comes from the
 * seller when its settlement failed (the authorization could still be used elsewhere until it expires).
 *
 * @param reasonCode a bounded code ({@code [a-z0-9_]{1,64}}), never free text from a facilitator
 */
public record PaymentFailed(
        EventMetadata meta,
        AuthorizationRef authorization,
        Money amount,
        String payTo,
        String resource,
        Book book,
        Finality finality,
        String reasonCode,
        @Nullable UUID paymentIntentId,
        @Nullable UUID runId) {

    public PaymentFailed {
        PaymentEvents.requireCommon(amount, payTo, resource);
        if (!PaymentEvents.REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode must match [a-z0-9_]{1,64}");
        }
    }
}
