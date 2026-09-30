package io.github.orhanyarkin.saiman.sellerapi.llm;

/** A payer or the day's unsettled-run budget is used up; no model call was made. Mapped to 429. */
public final class RunLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RunLimitExceededException() {
        super("model run limit reached", null, false, false);
    }
}
