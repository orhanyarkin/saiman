package io.github.orhanyarkin.saiman.modelrouter;

/**
 * The model-cost budget of one scope (a run) would be exceeded by the next round trip. Nothing was
 * sent to a provider. Distinct from {@link DailyCapExceededException}, which is the global cap.
 */
public class ScopeBudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScopeBudgetExceededException(String message) {
        super(message);
    }
}
