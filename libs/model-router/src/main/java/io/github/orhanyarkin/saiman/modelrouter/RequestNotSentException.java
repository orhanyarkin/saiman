package io.github.orhanyarkin.saiman.modelrouter;

/**
 * Thrown when a request provably never left this process (for example the API key is missing), so
 * the cost reservation for it can be given back. Any other failure after the router handed the
 * request to the provider SDK is treated as "maybe billed" and keeps its reservation.
 */
public class RequestNotSentException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public RequestNotSentException(String message) {
        super(message);
    }
}
