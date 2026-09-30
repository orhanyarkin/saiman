package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.shared.run.RunEventData;

/** How a {@link ResearchPipeline} ended: a report, or a fixed failure code. */
public sealed interface RunOutcome {

    static RunOutcome succeeded(RunEventData.Report report) {
        return new Succeeded(report);
    }

    static RunOutcome failed(FailureCode code) {
        return new Failed(code);
    }

    /** The report; its citations must come from the run's evidence (the pipeline's job, ADR-0014). */
    record Succeeded(RunEventData.Report report) implements RunOutcome {}

    record Failed(FailureCode code) implements RunOutcome {}
}
