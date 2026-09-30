package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import io.github.orhanyarkin.saiman.orchestrator.run.ResearchPipeline;
import io.github.orhanyarkin.saiman.orchestrator.run.RunContext;
import io.github.orhanyarkin.saiman.orchestrator.run.RunOutcome;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import java.util.List;
import java.util.function.Function;

/**
 * A test-only {@link ResearchPipeline} (T4b supplies the real one): each test sets the script it
 * wants; by default the run succeeds at once with a fixed report and no tool calls.
 */
public final class ScriptedPipeline implements ResearchPipeline {

    public static final RunEventData.Report DEFAULT_REPORT = new RunEventData.Report("scripted answer", List.of());

    private volatile Function<RunContext, RunOutcome> script = context -> RunOutcome.succeeded(DEFAULT_REPORT);

    public void script(Function<RunContext, RunOutcome> script) {
        this.script = script;
    }

    public void reset() {
        script = context -> RunOutcome.succeeded(DEFAULT_REPORT);
    }

    @Override
    public RunOutcome execute(RunContext context) {
        return script.apply(context);
    }
}
