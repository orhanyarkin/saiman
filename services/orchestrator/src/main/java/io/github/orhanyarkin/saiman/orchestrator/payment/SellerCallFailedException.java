package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.util.UUID;

/** The seller call failed without an unresolved payment (see {@link #kind()}). */
public final class SellerCallFailedException extends PaidCallException {

    private static final long serialVersionUID = 1L;

    /** Why the call failed. */
    public enum Kind {
        /** The seller could not be reached, or its circuit breaker is open. Nothing was paid. */
        UNAVAILABLE,
        /** The seller answered with a non-2xx status before any payment (see {@link #status()}). */
        HTTP_ERROR,
        /** The payment was aborted before the signature was sent; the reservation was released. */
        ABORTED,
        /** Paying is not configured (no {@code x402.client} signer). Nothing was sent. */
        NOT_CONFIGURED,
        /** The response could not be read; {@link #paid()} tells whether a payment settled. */
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final int status;
    private final boolean paid;

    public SellerCallFailedException(UUID paymentIntentId, Kind kind, int status, boolean paid) {
        super(paymentIntentId, "seller call failed: " + kind.name());
        this.kind = kind;
        this.status = status;
        this.paid = paid;
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status for {@link Kind#HTTP_ERROR}, else {@code -1}. */
    public int status() {
        return status;
    }

    /** True if the payment for this call settled even though the response is unusable. */
    public boolean paid() {
        return paid;
    }
}
