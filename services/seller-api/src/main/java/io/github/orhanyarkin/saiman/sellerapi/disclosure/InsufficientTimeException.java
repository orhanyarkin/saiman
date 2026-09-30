package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/**
 * The request has too little authorization time left to start a model call (see {@code
 * GroundedGenerator#requireTimeForModel}). It is a property of this one request, not of the ticker
 * or the model, so it is deliberately NOT a {@link ModelUnavailableException}: it is never
 * negative-cached (otherwise any payer could poison a ticker for everyone by sending a nearly
 * expired authorization) and never makes a request the single-flight winner. The response is the
 * same 503 as for an unavailable model, and it is never settled.
 */
final class InsufficientTimeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InsufficientTimeException() {
        super("insufficient time", null, false, false);
    }
}
