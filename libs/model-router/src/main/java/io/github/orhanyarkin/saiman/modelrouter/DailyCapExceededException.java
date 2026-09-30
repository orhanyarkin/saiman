package io.github.orhanyarkin.saiman.modelrouter;

/**
 * The global daily USD cap for model calls is reached (ADR-0011). Distinct from {@link
 * DataClassViolationException} so callers can switch to replay mode on exactly this condition.
 */
public class DailyCapExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DailyCapExceededException(String message) {
        super(message);
    }
}
