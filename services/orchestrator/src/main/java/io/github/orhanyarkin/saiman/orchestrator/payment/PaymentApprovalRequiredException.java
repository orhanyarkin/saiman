package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.util.UUID;

/**
 * The payment is above the approval threshold: nothing was signed, the intent is AWAITING_APPROVAL
 * and {@link #approvalId()} waits for a human. After an APPROVE, re-send the same {@link
 * PaymentIntentHandle}; the seller's fresh 402 must match what was approved.
 */
public final class PaymentApprovalRequiredException extends PaidCallException {

    private static final long serialVersionUID = 1L;

    private final UUID approvalId;

    public PaymentApprovalRequiredException(UUID paymentIntentId, UUID approvalId) {
        super(paymentIntentId, "payment waits for approval");
        this.approvalId = approvalId;
    }

    public UUID approvalId() {
        return approvalId;
    }
}
