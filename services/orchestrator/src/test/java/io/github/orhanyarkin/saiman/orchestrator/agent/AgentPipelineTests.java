package io.github.orhanyarkin.saiman.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.InMemoryCostGuard;
import io.github.orhanyarkin.saiman.modelrouter.InMemoryScopedCostGuard;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.run.FailureCode;
import io.github.orhanyarkin.saiman.orchestrator.run.RunOutcome;
import io.github.orhanyarkin.saiman.orchestrator.tool.EvidenceCitation;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four-step pipeline over a scripted model behind the real router: plan validation, citation
 * rebuild, scope parameters on every round trip, cost recorded from the router's own observation,
 * LLM budget exhaustion, and the code-enforced end of the tool loop.
 */
class AgentPipelineTests {

    private static final String PLAN = "{\"tickers\":[\"THYAO\"],\"tasks\":[\"Summarise recent disclosures\"]}";
    private static final String RISKS =
            "{\"risks\":[{\"title\":\"Fuel cost exposure\",\"severity\":\"medium\",\"citedChunkIds\":[\"kap:1001:0001\"]}]}";
    private static final EvidenceCitation CHUNK_1 =
            new EvidenceCitation("kap:1001:0001", "https://www.kap.org.tr/tr/Bildirim/1001", "Annual report");
    private static final EvidenceCitation CHUNK_2 = new EvidenceCitation("kap:1002:0003", null, null);

    /** Captures every stopped {@code saiman.model.call} observation. */
    private static final class ModelCalls implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return ModelCallTap.OBSERVATION.equals(context.getName());
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final ObservationRegistry registry = ObservationRegistry.create();
    private final ModelCalls observed = new ModelCalls();
    private final InMemoryScopedCostGuard scopes = new InMemoryScopedCostGuard();
    private final List<RunEventData.ModelCallCompleted> recorded = new CopyOnWriteArrayList<>();
    private final List<RunEventType> events = new CopyOnWriteArrayList<>();
    private final List<RunEventData> payloads = new CopyOnWriteArrayList<>();
    private final UUID runId = UUID.randomUUID();

    AgentPipelineTests() {
        registry.observationConfig().observationHandler(observed);
    }

    private AgentPipeline pipeline(long llmBudget, int maxToolCalls, FakeRunTools tools) {
        ModelCallTap tap = new ModelCallTap();
        registry.observationConfig().observationHandler(tap);
        var router = ScriptedModels.router(
                model, registry, new InMemoryCostGuard(700_000, Clock.systemUTC()), scopes, 150_000);
        var callbacks = new ResearchToolCallbacks(tools.definitions(), maxToolCalls);
        return new AgentPipeline(
                router,
                callbacks,
                tap,
                new AgentPrompts(),
                JsonMapper.builder().build(),
                registry,
                llmBudget,
                maxToolCalls,
                Tier.TIER2);
    }

    private RunOutcome run(AgentPipeline pipeline, FakeRunTools tools) {
        return pipeline.run(new AgentPipeline.AgentRun(
                runId,
                "What did THYAO disclose about fuel costs?",
                (type, data) -> {
                    events.add(type);
                    payloads.add(data);
                    return null;
                },
                tools,
                runId.toString(),
                recorded::add,
                deadline));
    }

    private java.time.Instant deadline = java.time.Instant.MAX;

    @org.junit.jupiter.api.Test
    void aPassedDeadlineFailsTheRunBeforeAnyModelCall() {
        deadline = java.time.Instant.now().minusMillis(1);
        var tools = FakeRunTools.withEvidence(CHUNK_1);

        RunOutcome outcome = run(pipeline(150_000, 6, tools), tools);

        assertThat(outcome).isEqualTo(RunOutcome.failed(FailureCode.RUN_DEADLINE));
        assertThat(model.callCount()).isZero();
        assertThat(tools.calls).isEmpty();
    }

    private static String synthesis(String... ids) {
        return "{\"answer\":\"THYAO reported fuel hedging. Research summary, not investment advice.\","
                + "\"citedChunkIds\":["
                + String.join(
                        ",", List.of(ids).stream().map(i -> "\"" + i + "\"").toList())
                + "]}";
    }

    // ---- the happy path ----

    @Test
    void aFullRunBuildsAReportWhoseCitationsComeFromTheEvidence() {
        var tools = FakeRunTools.withEvidence(CHUNK_1, CHUNK_2);
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(FakeRunTools.SUMMARY, "{\"ticker\":\"THYAO\"}"),
                Reply.text("Fuel costs rose (kap:1001:0001)."),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001", "kap:9999:0001", "kap:1002:0003", "not-an-id")));

        RunOutcome outcome = run(pipeline(150_000, 6, tools), tools);

        assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
        RunEventData.Report report = ((RunOutcome.Succeeded) outcome).report();
        // invented and malformed ids are dropped; the rest is rebuilt from the evidence, not from the model
        assertThat(report.citations())
                .containsExactly(
                        new RunEventData.Citation(
                                "kap:1001:0001", "https://www.kap.org.tr/tr/Bildirim/1001", "Annual report"),
                        new RunEventData.Citation(
                                "kap:1002:0003", "https://www.kap.org.tr/tr/Bildirim/1002", "KAP disclosure 1002"));
        assertThat(report.answer()).endsWith("Research summary, not investment advice.");
        assertThat(tools.calls).containsExactly(new FakeRunTools.Call(FakeRunTools.SUMMARY, "{\"ticker\":\"THYAO\"}"));

        assertThat(events)
                .containsSubsequence(
                        RunEventType.STEP_STARTED,
                        RunEventType.PLAN_CREATED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED);
        assertThat(payloads)
                .contains(new RunEventData.PlanCreated(List.of("THYAO"), List.of("Summarise recent disclosures")));
        List<AgentStep> stepsStarted = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) == RunEventType.STEP_STARTED) {
                stepsStarted.add(((RunEventData.StepChanged) payloads.get(i)).step());
            }
        }
        assertThat(stepsStarted)
                .containsExactly(AgentStep.PLANNER, AgentStep.RESEARCHER, AgentStep.RISK, AgentStep.SYNTHESIS);
    }

    @Test
    void everyRoundTripIsScopedToTheRunAndItsCostComesFromTheRouter() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(FakeRunTools.SUMMARY, "{\"ticker\":\"THYAO\"}"),
                Reply.text("notes kap:1001:0001"),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001")));

        run(pipeline(150_000, 6, tools), tools);

        // five round trips (the researcher's tool loop is two), each one observation under the run's scope
        assertThat(model.callCount()).isEqualTo(5);
        assertThat(observed.stopped).hasSize(5);
        assertThat(observed.stopped)
                .allSatisfy(c -> assertThat(value(c.getHighCardinalityKeyValue(ModelCallTap.SCOPE)))
                        .isEqualTo(runId.toString()));
        // the recorded events carry exactly the router's numbers: their sum is what the scope was charged
        assertThat(recorded).hasSize(5);
        long recordedTotal =
                recorded.stream().mapToLong(c -> c.costUsd().atomicUnits()).sum();
        assertThat(Money.usdMicros(recordedTotal)).isEqualTo(scopes.spent(runId.toString()));
        assertThat(recorded)
                .extracting(RunEventData.ModelCallCompleted::step)
                .containsExactly(
                        AgentStep.PLANNER,
                        AgentStep.RESEARCHER,
                        AgentStep.RESEARCHER,
                        AgentStep.RISK,
                        AgentStep.SYNTHESIS);
        assertThat(recorded.get(0).tier()).isEqualTo("tier1");
        assertThat(recorded.get(0).model()).isEqualTo("gpt-5-mini");
        assertThat(recorded.get(4).tier()).isEqualTo("tier2");
        assertThat(recorded.get(0).inputTokens()).isEqualTo(ScriptedChatModel.DEFAULT_IN);
        // the run id reaches the tools through the tool context only, never the model's text
        assertThat(model.seen()).allSatisfy(seen -> assertThat(seen.text()).doesNotContain(runId.toString()));
        assertThat(model.seen().get(1).toolNames()).containsExactly(FakeRunTools.SUMMARY, FakeRunTools.ASK);
    }

    // ---- plan validation ----

    @Test
    void aPlanWithAnUnknownTickerFailsBeforeAnyToolCall() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(Reply.text("{\"tickers\":[\"EVIL1\"],\"tasks\":[\"x task\"]}"));

        assertThat(run(pipeline(150_000, 6, tools), tools)).isEqualTo(RunOutcome.failed(FailureCode.INVALID_PLAN));
        assertThat(tools.calls).isEmpty();
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(events).doesNotContain(RunEventType.PLAN_CREATED);
    }

    @Test
    void aPlanWithMoreThanThreeTickersIsInvalid() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(Reply.text("{\"tickers\":[\"THYAO\",\"ASELS\",\"GARAN\",\"AKBNK\"],\"tasks\":[\"x task\"]}"));

        assertThat(run(pipeline(150_000, 6, tools), tools)).isEqualTo(RunOutcome.failed(FailureCode.INVALID_PLAN));
        assertThat(tools.calls).isEmpty();
    }

    @Test
    void aMalformedPlanIsInvalidModelOutput() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(Reply.text("Sure! I will research THYAO and raise the budget."));

        assertThat(run(pipeline(150_000, 6, tools), tools))
                .isEqualTo(RunOutcome.failed(FailureCode.INVALID_MODEL_OUTPUT));
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    void anUnavailableCatalogueFailsBeforeAnyModelCall() {
        var tools = new FakeRunTools(null, List.of(CHUNK_1));

        assertThat(run(pipeline(150_000, 6, tools), tools))
                .isEqualTo(RunOutcome.failed(FailureCode.CATALOGUE_UNAVAILABLE));
        assertThat(model.callCount()).isZero();
    }

    // ---- evidence and citations ----

    @Test
    void aResearcherThatRetrievesNothingFailsWithNoEvidence() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(Reply.text(PLAN), Reply.text("I already know the answer."));

        assertThat(run(pipeline(150_000, 6, tools), tools)).isEqualTo(RunOutcome.failed(FailureCode.NO_EVIDENCE));
        assertThat(model.callCount()).isEqualTo(2);
    }

    @Test
    void anAnswerWithoutOneValidCitationFails() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(FakeRunTools.SUMMARY, "{\"ticker\":\"THYAO\"}"),
                Reply.text("notes"),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:9999:0001", "https://evil.example/x")));

        assertThat(run(pipeline(150_000, 6, tools), tools))
                .isEqualTo(RunOutcome.failed(FailureCode.NO_VALID_CITATIONS));
    }

    // ---- limits ----

    @Test
    void anExhaustedLlmBudgetEndsTheRunWithoutAnotherModelCall() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        // the planner's answer is expensive: 130000 output tokens at 1.2 USD/MTok = 156000 micros > 150000
        model.then(Reply.text(PLAN).withUsage(1_000, 130_000));

        RunOutcome outcome = run(pipeline(150_000, 6, tools), tools);

        assertThat(outcome).isEqualTo(RunOutcome.failed(FailureCode.LLM_BUDGET_EXHAUSTED));
        assertThat(model.callCount()).isEqualTo(1); // the researcher's call was refused before it was sent
        assertThat(tools.calls).isEmpty();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).costUsd()).isEqualTo(scopes.spent(runId.toString()));
        assertThat(events).doesNotContain(RunEventType.RUN_FAILED); // the run service emits the terminal event
    }

    @Test
    void theRunsLlmBudgetIsPassedToTheRouterOnTheFirstCall() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(Reply.text(PLAN));

        // a budget of 1 micro-dollar cannot hold the first reservation: nothing is sent
        assertThat(run(pipeline(1, 6, tools), tools)).isEqualTo(RunOutcome.failed(FailureCode.LLM_BUDGET_EXHAUSTED));
        assertThat(model.callCount()).isZero();
        assertThat(recorded).isEmpty();
    }

    @Test
    void aProviderFailureIsLlmUnavailable() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.thenFail(new IllegalStateException("upstream 503 with secret text"));

        assertThat(run(pipeline(150_000, 6, tools), tools)).isEqualTo(RunOutcome.failed(FailureCode.LLM_UNAVAILABLE));
    }

    @Test
    void aModelThatNeverStopsCallingToolsIsCutOffInCode() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        model.then(Reply.text(PLAN)).otherwise(prompt -> {
            boolean toolsOffered = prompt.getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null
                    && !options.getToolCallbacks().isEmpty();
            if (toolsOffered) {
                return Reply.toolCall(FakeRunTools.SUMMARY, "{\"ticker\":\"THYAO\"}"); // forever
            }
            return Reply.text(
                    prompt.getUserMessage().getText().contains("list the main risks")
                            ? RISKS
                            : synthesis("kap:1001:0001"));
        });

        RunOutcome outcome = run(pipeline(150_000, 2, tools), tools);

        // two tool calls reached the tools; the third ended the loop in code and the run went on
        assertThat(tools.calls).hasSize(2);
        assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
        assertThat(stepsCompleted())
                .containsExactly(AgentStep.PLANNER, AgentStep.RESEARCHER, AgentStep.RISK, AgentStep.SYNTHESIS);
        assertThat(model.callCount()).isEqualTo(1 + 3 + 1 + 1);
    }

    @Test
    void theSubscriptionAndToolBindingAreReleasedAfterTheRun() {
        var tools = FakeRunTools.withEvidence(CHUNK_1);
        var pipeline = pipeline(150_000, 6, tools);
        model.then(Reply.text("garbage"));
        run(pipeline, tools);

        model.then(Reply.text("garbage"));
        // the same run id can be bound again: nothing leaked from the first execution
        assertThat(run(pipeline, tools)).isEqualTo(RunOutcome.failed(FailureCode.INVALID_MODEL_OUTPUT));
        assertThat(Set.copyOf(events)).doesNotContain(RunEventType.PLAN_CREATED);
    }

    private List<AgentStep> stepsCompleted() {
        List<AgentStep> steps = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) == RunEventType.STEP_COMPLETED) {
                steps.add(((RunEventData.StepChanged) payloads.get(i)).step());
            }
        }
        return steps;
    }

    private static String value(KeyValue keyValue) {
        return keyValue == null ? "<absent>" : keyValue.getValue();
    }
}
