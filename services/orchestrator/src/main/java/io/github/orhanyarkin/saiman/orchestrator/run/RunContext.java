package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.orchestrator.events.RunEventEmitter;
import io.github.orhanyarkin.saiman.orchestrator.tool.RunToolSession;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything a {@link ResearchPipeline} gets for one run. Nothing in here can change a limit: the
 * budget is informational (the spend-control plane enforces it), and the tools take only a ticker
 * and a question.
 *
 * @param question the user's question, cleaned (3..500 characters, no control or bidi characters);
 *     still untrusted text
 * @param budget the run's payment budget (USDC), immutable
 * @param events appends this run's step events
 * @param tools this run's paid research tools, ticker catalogue and evidence
 * @param costScopeId the router cost scope for every LLM call of this run ({@code
 *     RouterAdvisorParams.COST_SCOPE})
 * @param modelCalls records each LLM round trip's cost and event
 * @param deadline the run's wall-clock deadline; a pipeline starts no step past it and fails with
 *     {@link FailureCode#RUN_DEADLINE}
 */
public record RunContext(
        UUID runId,
        String question,
        Money budget,
        RunEventEmitter events,
        RunToolSession tools,
        String costScopeId,
        ModelCallRecorder modelCalls,
        Instant deadline) {}
