package io.github.orhanyarkin.saiman.orchestrator.run;

/** Test-only access to package-private run internals. */
public final class RunTestAccess {

    private RunTestAccess() {}

    public static int activeRuns(RunService runs) {
        return runs.activeRuns();
    }
}
