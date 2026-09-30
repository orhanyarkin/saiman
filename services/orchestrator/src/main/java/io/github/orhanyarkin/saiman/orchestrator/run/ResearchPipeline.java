package io.github.orhanyarkin.saiman.orchestrator.run;

/**
 * The research agents (planner, researcher, risk, synthesis; ADR-0014), implemented in T4b. Runs on
 * the run's virtual thread, inside the run's {@code saiman.run} observation.
 *
 * <p>Contract: call paid tools only through {@link RunContext#tools()}, emit step events through
 * {@link RunContext#events()}, record every model round trip through {@link
 * RunContext#modelCalls()}, and scope every LLM call with {@link RunContext#costScopeId()}. Don't
 * emit {@code RUN_STARTED}, {@code RUN_COMPLETED} or {@code RUN_FAILED}: the run service does. An
 * exception is treated as {@link FailureCode#INTERNAL_ERROR}.
 */
@FunctionalInterface
public interface ResearchPipeline {

    RunOutcome execute(RunContext context);
}
