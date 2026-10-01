package io.github.orhanyarkin.saiman.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.ModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.run.FailureCode;
import io.github.orhanyarkin.saiman.orchestrator.run.RunOutcome;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.saiman.orchestrator.tool.EvidenceCitation;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agents as Boot wires them: the auto-configured router (cost scope required, in-memory guards
 * in tests) over the scripted model, {@link ModelCallTap} registered on the application's
 * observation registry, and the tool adapter generated from the real tool catalogue.
 */
class AgentWiringTests extends RunTestSupport {

    @Autowired
    private AgentPipeline agents;

    @Autowired
    private ResearchToolCallbacks callbacks;

    @Autowired
    private ModelRouter router;

    @Autowired
    private JsonMapper json;

    @Test
    void theModelSeesExactlyTheTwoCatalogueToolsWithTickerAndQuestionParameters() {
        assertThat(callbacks.callbacks())
                .extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("disclosureSummary", "askDisclosures");
        for (ToolCallback callback : callbacks.callbacks()) {
            JsonNode schema = json.readTree(callback.getToolDefinition().inputSchema());
            Set<String> properties = new HashSet<>();
            schema.get("properties").properties().forEach(p -> properties.add(p.getKey()));
            assertThat(properties)
                    .isEqualTo(
                            callback.getToolDefinition().name().equals("askDisclosures")
                                    ? Set.of("ticker", "question")
                                    : Set.of("ticker"));
        }
    }

    @Test
    void theAutoConfiguredRouterChargesTheRunScopeAndTheTapRecordsEveryRoundTrip() {
        UUID runId = UUID.randomUUID();
        var tools = FakeRunTools.withEvidence(new EvidenceCitation("kap:1001:0001", null, "Annual report"));
        List<RunEventData.ModelCallCompleted> recorded = new CopyOnWriteArrayList<>();
        model.then(
                Reply.text("{\"tickers\":[\"THYAO\"],\"tasks\":[\"Summarise\"]}"),
                Reply.toolCall("disclosureSummary", "{\"ticker\":\"THYAO\"}"),
                Reply.text("notes"),
                Reply.text("{\"risks\":[]}"),
                Reply.text("{\"answer\":\"Summary. Research summary, not investment advice.\","
                        + "\"citedChunkIds\":[\"kap:1001:0001\"]}"));

        RunOutcome outcome = agents.run(new AgentPipeline.AgentRun(
                runId, "What did THYAO disclose?", (type, data) -> null, tools, runId.toString(), recorded::add));

        assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
        assertThat(recorded)
                .extracting(RunEventData.ModelCallCompleted::step)
                .containsExactly(
                        AgentStep.PLANNER,
                        AgentStep.RESEARCHER,
                        AgentStep.RESEARCHER,
                        AgentStep.RISK,
                        AgentStep.SYNTHESIS);
        assertThat(recorded)
                .allSatisfy(call -> assertThat(call.costUsd().atomicUnits()).isPositive());
    }

    @Test
    void theApplicationRouterRefusesAnUnscopedCall() {
        model.then(Reply.text("never sent"));

        Throwable refused = catchThrowable(() -> router.chatClient(Tier.TIER1, DataClass.INTERNAL)
                .prompt()
                .user("hi")
                .call()
                .content());

        // Refused before anything is sent. With tracing on, the router currently surfaces this as
        // the tracing handler's IllegalStateException (it reports the error on an observation it has
        // not started yet) rather than RequestNotSentException: reported to the router's owner.
        assertThat(refused).isInstanceOf(IllegalStateException.class);
        assertThat(AgentPipeline.classify(refused)).isEqualTo(FailureCode.LLM_UNAVAILABLE);
        assertThat(model.callCount()).isZero();
    }
}
