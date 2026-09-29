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

    public FacilitatorException(String message) {
        super(message);
    }

    public FacilitatorException(String message, Throwable cause) {
        super(message, cause);
    }
}
