package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.util.UUID;

/**
 * A paid call did not produce a usable response. Messages are fixed strings: nothing from the
 * seller's response or the model ever reaches one (they may be shown to a model as tool output).
 */
public abstract sealed class PaidCallException extends RuntimeException
        permits PaymentDeniedException,
                PaymentApprovalRequiredException,
                PaymentOutcomeUnknownException,
                SellerCallFailedException {

    private static final long serialVersionUID = 1L;

    private final UUID paymentIntentId;

    PaidCallException(UUID paymentIntentId, String message) {
        super(message);
        this.paymentIntentId = paymentIntentId;
    }

    public UUID paymentIntentId() {
        return paymentIntentId;
    }
}
