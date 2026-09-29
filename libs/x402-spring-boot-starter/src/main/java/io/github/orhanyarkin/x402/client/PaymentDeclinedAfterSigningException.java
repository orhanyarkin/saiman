package io.github.orhanyarkin.x402.client;

/**
 * A more specific {@link AmbiguousPaymentException}: the server declined a payment-carrying
 * request (a second 402 on the paid retry) <em>after</em> a signed EIP-3009 authorization already
 * left this process.
 *
 * <p>Distinguished from a plain pre-signing rejection ({@link PaymentRejectedException}) and from
 * a generic {@link AmbiguousPaymentException} so that a caller — in particular a future
 * spend-control plane reconciling held reservations across process restarts — can recognize
 * specifically "the seller said no, but the already-sent signature could still be settled
 * elsewhere or later (until {@code validBefore})" and never mistake it for an ordinary,
 * safe-to-retry-under-a-fresh-key 402. {@link X402PaymentInterceptor} never releases the {@link
 * SpendReservation} for this outcome; see {@link SpendGuard#release}'s Javadoc.
 *
 * <p>Carries only the retried request's HTTP status code — never its body or headers, which may
 * contain server-controlled content this starter does not sanitize for exception messages.
 */
public final class PaymentDeclinedAfterSigningException extends AmbiguousPaymentException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public PaymentDeclinedAfterSigningException(int statusCode) {
        super("payment-carrying request was declined with HTTP " + statusCode
                + " after a signature was already sent; outcome is ambiguous, not a plain rejection");
        this.statusCode = statusCode;
    }

    /** The retried request's HTTP status code (402 in every case this starter produces today). */
    public int statusCode() {
        return statusCode;
    }
}
