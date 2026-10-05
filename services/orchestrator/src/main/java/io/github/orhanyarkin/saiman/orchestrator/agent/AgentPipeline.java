package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.modelrouter.DailyCapExceededException;
import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.DataClassViolationException;
import io.github.orhanyarkin.saiman.modelrouter.ModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.RouterAdvisorParams;
import io.github.orhanyarkin.saiman.modelrouter.ScopeBudgetExceededException;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import io.github.orhanyarkin.saiman.orchestrator.budget.RunLimitsProperties;
import io.github.orhanyarkin.saiman.orchestrator.budget.SpendProperties;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventEmitter;
import io.github.orhanyarkin.saiman.orchestrator.run.FailureCode;
import io.github.orhanyarkin.saiman.orchestrator.run.ModelCallRecorder;
import io.github.orhanyarkin.saiman.orchestrator.run.ResearchPipeline;
import io.github.orhanyarkin.saiman.orchestrator.run.RunContext;
import io.github.orhanyarkin.saiman.orchestrator.run.RunOutcome;
import io.github.orhanyarkin.saiman.orchestrator.tool.EvidenceCitation;
import io.github.orhanyarkin.saiman.orchestrator.tool.UntrustedText;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The research pipeline of ADR-0014: a fixed PLANNER -> RESEARCHER -> RISK -> SYNTHESIS sequence,
 * not an open-ended agent. Each step is bracketed by {@code STEP_STARTED}/{@code STEP_COMPLETED} and
 * runs in a {@code saiman.run.step} observation, a child of the run's {@code saiman.run} root.
 *
 * <ul>
 *   <li><b>Planner</b> (TIER1, structured output): 1-3 tickers and 1-4 tasks, validated by code
 *       against the seller's ticker catalogue; emits {@code PLAN_CREATED}.
 *   <li><b>Researcher</b> (TIER1, tools): the catalogue's tools as Spring AI tool callbacks
 *       ({@link ResearchToolCallbacks}); Spring AI's own tool-calling advisor runs the loop, the
 *       router's cost advisor charges each round trip, and every tool call goes through the paid-tool
 *       gateway. The run id travels in the tool context, invisible to the model.
 *   <li><b>Risk</b> (TIER1, no tools, structured output).
 *   <li><b>Synthesis</b> (TIER2 by config, structured output): code keeps only cited ids present in
 *       the run's evidence and rebuilds the citations from it; at least one is required.
 * </ul>
 *
 * Every model call names the run as the router's cost scope with the run's LLM budget; the router's
 * {@code saiman.model.call} observation feeds {@link ModelCallTap}, which records each round trip's
 * cost on the run. A scope-budget refusal ends the run with {@code LLM_BUDGET_EXHAUSTED}, a daily-cap
 * refusal with {@code LLM_DAILY_CAP_REACHED}, and neither makes a further model call. Nothing a model writes becomes a limit, a URL, a payee or an amount.
 */
@Component
@EnableConfigurationProperties(AgentProperties.class)
public class AgentPipeline implements ResearchPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(AgentPipeline.class);
    static final String STEP_OBSERVATION = "saiman.run.step";

    private final ModelRouter router;
    private final ResearchToolCallbacks toolCallbacks;
    private final ModelCallTap tap;
    private final AgentPrompts prompts;
    private final AgentOutputs outputs;
    private final ObservationRegistry observations;
    private final long llmBudgetUsdMicros;
    private final int maxToolCalls;
    private final Tier synthesisTier;

    @Autowired
    public AgentPipeline(
            ModelRouter router,
            ResearchToolCallbacks toolCallbacks,
            ModelCallTap tap,
            AgentPrompts prompts,
            JsonMapper json,
            ObservationRegistry observations,
            RunLimitsProperties limits,
            SpendProperties spend,
            AgentProperties agents) {
        this(
                router,
                toolCallbacks,
                tap,
                prompts,
                json,
                observations,
                limits.llmBudgetUsdMicros(),
                spend.maxToolCallsPerRun(),
                agents.synthesisTier());
    }

    /** For tests: the same pipeline with explicit limits. */
    AgentPipeline(
            ModelRouter router,
            ResearchToolCallbacks toolCallbacks,
            ModelCallTap tap,
            AgentPrompts prompts,
            JsonMapper json,
            ObservationRegistry observations,
            long llmBudgetUsdMicros,
            int maxToolCalls,
            Tier synthesisTier) {
        this.router = router;
        this.toolCallbacks = toolCallbacks;
        this.tap = tap;
        this.prompts = prompts;
        this.outputs = new AgentOutputs(json);
        this.observations = observations;
        this.llmBudgetUsdMicros = llmBudgetUsdMicros;
        this.maxToolCalls = maxToolCalls;
        this.synthesisTier = synthesisTier;
    }

    /** Everything one execution needs; {@link RunContext} with the tools behind {@link RunTools}. */
    record AgentRun(
            UUID runId,
            String question,
            RunEventEmitter events,
            RunTools tools,
            String costScopeId,
            ModelCallRecorder modelCalls,
            Instant deadline) {}

    /** A step ended the run with a fixed code. */
    private static final class StepFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final FailureCode code;

        StepFailure(FailureCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }
    }

    @Override
    public RunOutcome execute(RunContext context) {
        return run(new AgentRun(
                context.runId(),
                context.question(),
                context.events(),
                RunTools.of(context.tools()),
                context.costScopeId(),
                context.modelCalls(),
                context.deadline()));
    }

    RunOutcome run(AgentRun run) {
        try (ModelCallTap.Subscription calls = tap.subscribe(run.costScopeId(), run.modelCalls());
                ResearchToolCallbacks.Binding binding = toolCallbacks.bind(run.runId(), run.tools())) {
            String question = UntrustedText.forModel(run.question(), UntrustedText.MAX_QUESTION);
            AgentOutputs.Plan plan = step(run, calls, AgentStep.PLANNER, () -> plan(run, question));
            String notes = step(run, calls, AgentStep.RESEARCHER, () -> research(run, question, plan));
            List<AgentOutputs.Risk> risks = step(run, calls, AgentStep.RISK, () -> risks(run, question, notes));
            RunEventData.Report report =
                    step(run, calls, AgentStep.SYNTHESIS, () -> synthesise(run, question, notes, risks));
            return RunOutcome.succeeded(report);
        } catch (StepFailure failure) {
            return RunOutcome.failed(failure.code);
        }
    }

    /** Runs one step inside its events and its child observation; checks the cost recording after it. */
    private <T> T step(AgentRun run, ModelCallTap.Subscription calls, AgentStep step, Supplier<T> body) {
        requireBeforeDeadline(run);
        calls.step(step);
        run.events().emit(RunEventType.STEP_STARTED, new RunEventData.StepChanged(step));
        Observation observation = Observation.createNotStarted(STEP_OBSERVATION, observations)
                .contextualName("run-step-" + step.name().toLowerCase(Locale.ROOT))
                .lowCardinalityKeyValue("step", step.name().toLowerCase(Locale.ROOT))
                .start();
        String outcome = "failed";
        try (Observation.Scope scope = observation.openScope()) {
            T result = body.get();
            if (calls.failed()) {
                throw new StepFailure(FailureCode.INTERNAL_ERROR);
            }
            requireBeforeDeadline(run);
            outcome = "completed";
            run.events().emit(RunEventType.STEP_COMPLETED, new RunEventData.StepChanged(step));
            return result;
        } catch (StepFailure failure) {
            outcome = failure.code.name().toLowerCase(Locale.ROOT);
            throw failure;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            observation.stop();
        }
    }

    private static void requireBeforeDeadline(AgentRun run) {
        if (!Instant.now().isBefore(run.deadline())) {
            throw new StepFailure(FailureCode.RUN_DEADLINE);
        }
    }

    private AgentOutputs.Plan plan(AgentRun run, String question) {
        Set<String> catalogue = run.tools().knownTickers().orElse(null);
        if (catalogue == null || catalogue.isEmpty()) {
            throw new StepFailure(FailureCode.CATALOGUE_UNAVAILABLE);
        }
        String text = call(run, Tier.TIER1, prompts.planner(question, String.join(", ", catalogue)), false);
        AgentOutputs.Plan plan = parse(() -> outputs.plan(text, catalogue));
        run.events().emit(RunEventType.PLAN_CREATED, new RunEventData.PlanCreated(plan.tickers(), plan.tasks()));
        return plan;
    }

    private String research(AgentRun run, String question, AgentOutputs.Plan plan) {
        String planText =
                "tickers: " + String.join(", ", plan.tickers()) + "\ntasks:\n- " + String.join("\n- ", plan.tasks());
        String notes;
        try {
            notes = call(run, Tier.TIER1, prompts.researcher(question, planText, maxToolCalls), true);
        } catch (ResearchLimitReachedException e) {
            notes = ""; // the loop was cut in code; go on with the evidence retrieved so far
        }
        if (run.tools().allEvidence().isEmpty()) {
            throw new StepFailure(FailureCode.NO_EVIDENCE);
        }
        return AgentOutputs.scrub(notes == null ? "" : notes, AgentOutputs.MAX_NOTES);
    }

    private List<AgentOutputs.Risk> risks(AgentRun run, String question, String notes) {
        String text = call(run, Tier.TIER1, prompts.risk(question, notes, evidence(run)), false);
        return parse(() -> outputs.risks(text, run.tools()::evidence));
    }

    private RunEventData.Report synthesise(AgentRun run, String question, String notes, List<AgentOutputs.Risk> risks) {
        StringBuilder riskText = new StringBuilder();
        for (AgentOutputs.Risk risk : risks) {
            riskText.append("- [")
                    .append(risk.severity())
                    .append("] ")
                    .append(risk.title())
                    .append(risk.chunkIds().isEmpty() ? "" : " (" + String.join(", ", risk.chunkIds()) + ")")
                    .append('\n');
        }
        String text = call(
                run,
                synthesisTier,
                prompts.synthesis(question, notes, riskText.toString().strip(), evidence(run)),
                false);
        return parse(() -> outputs.report(text, run.tools()::evidence));
    }

    /** The evidence as the model sees it: one line per retrieved chunk (id and sanitised title). */
    private static String evidence(AgentRun run) {
        StringBuilder text = new StringBuilder();
        for (EvidenceCitation citation : run.tools().allEvidence()) {
            text.append(citation.chunkId());
            if (citation.title() != null) {
                text.append(" | ").append(citation.title());
            }
            text.append('\n');
        }
        return text.toString().strip();
    }

    /**
     * One {@code ChatClient} call, scoped to the run's LLM budget. Router refusals and provider
     * failures become fixed failure codes; a {@link ResearchLimitReachedException} passes through.
     */
    private @Nullable String call(AgentRun run, Tier tier, String user, boolean withTools) {
        try {
            ChatClient.ChatClientRequestSpec request = router.chatClient(tier, DataClass.INTERNAL)
                    .prompt()
                    .advisors(a -> a.param(RouterAdvisorParams.COST_SCOPE, run.costScopeId())
                            .param(RouterAdvisorParams.COST_SCOPE_BUDGET_USD_MICROS, llmBudgetUsdMicros))
                    .system(prompts.system())
                    .user(user);
            if (withTools) {
                request = request.tools(toolCallbacks
                                .callbacks()
                                .toArray()) // tools(Object...) takes ToolCallbacks; toolCallbacks(..) is deprecated
                        .toolContext(toolCallbacks.toolContext(run.runId()));
            }
            return request.call().content();
        } catch (RuntimeException e) {
            if (causedBy(e, ResearchLimitReachedException.class)) {
                throw new ResearchLimitReachedException();
            }
            FailureCode code = classify(e);
            // Never the message: it may carry provider or model text.
            LOG.warn(
                    "Model call of run {} failed ({}, {})",
                    run.runId(),
                    e.getClass().getSimpleName(),
                    code);
            throw new StepFailure(code);
        }
    }

    private static <T> T parse(Supplier<T> parser) {
        try {
            return parser.get();
        } catch (AgentOutputs.OutputException e) {
            throw new StepFailure(e.code());
        }
    }

    static FailureCode classify(Throwable failure) {
        if (causedBy(failure, ScopeBudgetExceededException.class)) {
            return FailureCode.LLM_BUDGET_EXHAUSTED;
        }
        if (causedBy(failure, DailyCapExceededException.class)) {
            return FailureCode.LLM_DAILY_CAP_REACHED;
        }
        if (causedBy(failure, DataClassViolationException.class)) {
            return FailureCode.INTERNAL_ERROR;
        }
        return FailureCode.LLM_UNAVAILABLE;
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 16; t = t.getCause(), depth++) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
