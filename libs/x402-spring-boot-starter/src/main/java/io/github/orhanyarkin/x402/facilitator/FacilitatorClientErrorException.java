package io.github.orhanyarkin.x402.facilitator;

/**
 * The facilitator responded with a {@code 4xx} status (including {@code 429}): the request itself
 * was rejected, not a transient failure. {@link HttpFacilitatorClient} never retries this -- unlike
 * a network error, a timeout or a {@code 5xx}, retrying the exact same request is expected to fail
 * the exact same way, so a retry would only add load and latency for no chance of success.
 */
public final class FacilitatorClientErrorException extends FacilitatorException {

    private static final long serialVersionUID = 1L;

    public FacilitatorClientErrorException(String message) {
        super(message, Failure.REJECTED, 0);
    }

    public FacilitatorClientErrorException(String message, int httpStatus) {
        super(message, Failure.REJECTED, httpStatus);
    }
}
