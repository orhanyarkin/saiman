package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.util.UUID;

/** The spend-control plane refused the payment before anything was signed. */
public final class PaymentDeniedException extends PaidCallException {

    private static final long serialVersionUID = 1L;

    private final DenyReason reason;

    public PaymentDeniedException(UUID paymentIntentId, DenyReason reason) {
        super(paymentIntentId, "payment denied: " + reason.name());
        this.reason = reason;
    }

    public DenyReason reason() {
        return reason;
    }
}
