package io.github.orhanyarkin.saiman.sellerapi.llm;

/** The run guard could not decide (Redis down, no payer): it fails closed, no model call. Mapped to 503. */
public final class RunGuardUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RunGuardUnavailableException() {
        super("model run guard unavailable", null, false, false);
    }
}
