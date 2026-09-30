package io.github.orhanyarkin.saiman.orchestrator.run;

/** A run was not admitted; {@link #reason()} decides the HTTP status. The message is fixed text. */
public final class RunAdmissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a run was not admitted. */
    public enum Reason {
        /** 400: the question is empty, too short, too long or only control characters. */
        INVALID_QUESTION("question must be 3 to 500 characters of text"),
        /** 400: the requested budget is not positive or above the configured maximum. */
        INVALID_BUDGET("budgetAtomic must be between 1 and the configured maximum run budget"),
        /** 429: {@code max-concurrent} runs are executing. */
        TOO_MANY_RUNS("too many runs are executing; try again later"),
        /** 503: the service is not ready (startup recovery has not finished). */
        NOT_READY("the orchestrator is not ready to accept runs yet");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }

    private final Reason reason;

    RunAdmissionException(Reason reason) {
        super(reason.detail());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
