package io.github.orhanyarkin.x402.facilitator;

/**
 * A call to the facilitator ({@code /verify}, {@code /settle} or {@code /supported}) failed: a
 * network error, a timeout, an open circuit breaker, a non-2xx or oversized response, or a
 * response that did not decode.
 *
 * <p>{@link #getMessage()} never includes the request or response body (ADR-0006 amendment): a
 * facilitator error message is attacker- or facilitator-influenceable and must not be echoed
 * verbatim into logs or a Problem Details response. Callers (see {@code
 * RequiresPaymentInterceptor}, {@code X402SettlementFilter}) treat any {@link FacilitatorException}
 * the same way regardless of its cause: verification failures reject with 402, settlement failures
 * drop the response body and keep the nonce claim. {@link FacilitatorClientErrorException} is the
 * one non-final subclass, for callers (and {@link HttpFacilitatorClient}'s own retry policy) that
 * need to distinguish "the facilitator rejected this request outright" from every other failure.
 */
public class FacilitatorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the call failed, coarsely; drives the {@code outcome} tag of the facilitator observations. */
    public enum Failure {
        /** Network error, timeout or a non-2xx, non-4xx status. */
        TRANSPORT,
        /** The local circuit breaker refused the call; nothing was sent. */
        CIRCUIT_OPEN,
        /** A 2xx answer that could not be decoded. */
        MALFORMED,
        /** The facilitator answered with a 4xx status. */
        REJECTED
    }

    private final Failure failure;
    private final int httpStatus;

    public FacilitatorException(String message) {
        this(message, Failure.TRANSPORT, 0);
    }

    public FacilitatorException(String message, Throwable cause) {
        super(message, cause);
        this.failure = Failure.TRANSPORT;
        this.httpStatus = 0;
    }

    /**
     * @param failure the coarse failure class
     * @param httpStatus the facilitator's HTTP status, or {@code 0} if there was no response
     */
    public FacilitatorException(String message, Failure failure, int httpStatus) {
        super(message);
        this.failure = failure;
        this.httpStatus = httpStatus;
    }

    /** The coarse failure class. */
    public Failure failure() {
        return failure;
    }

    /** The facilitator's HTTP status, or {@code 0} if no response was received. */
    public int httpStatus() {
        return httpStatus;
    }
}
