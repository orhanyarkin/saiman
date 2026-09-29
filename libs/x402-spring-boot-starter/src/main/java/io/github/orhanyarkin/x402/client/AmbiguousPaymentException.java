package io.github.orhanyarkin.x402.client;

/**
 * The outcome of a payment-carrying request could not be determined: it failed with a server
 * error, or answered 2xx without a decodable, successful {@code PAYMENT-RESPONSE} header. Money
 * may or may not have moved.
 *
 * <p>{@link X402PaymentInterceptor} never calls {@link SpendGuard#commit} or {@link
 * SpendGuard#release} for this outcome: the {@link SpendReservation} stays reserved, so a further
 * attempt with the same {@code Idempotency-Key} is refused rather than risking a double payment
 * (ADR-0008: fail closed on ambiguous outcomes).
 *
 * <p>Not {@code final}: {@link PaymentDeclinedAfterSigningException} is a more specific subtype
 * for the one case (a second 402 after a signature was already sent) common enough, and dangerous
 * enough to mistake for a plain pre-signing rejection, to warrant its own type.
 */
public class AmbiguousPaymentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AmbiguousPaymentException(String message) {
        super(message);
    }
}
