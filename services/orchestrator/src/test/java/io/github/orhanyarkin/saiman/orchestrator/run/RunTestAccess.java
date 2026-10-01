package io.github.orhanyarkin.saiman.orchestrator.run;

/** Test-only access to package-private run internals. */
public final class RunTestAccess {

    private RunTestAccess() {}

    public static int activeRuns(RunService runs) {
        return runs.activeRuns();
    }

    /** Ends a run as the run's own thread does (one transaction, terminal event). */
    public static void finishFailed(RunService runs, java.util.UUID runId, FailureCode code) {
        runs.finish(runId, RunOutcome.failed(code));
    }
}
