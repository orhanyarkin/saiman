package io.github.orhanyarkin.saiman.orchestrator.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.run.RunStatus;
import io.github.orhanyarkin.saiman.orchestrator.run.RunSummary;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.AgentRunTestSupport;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance 1 (docs/PLAN.md M3): a research run completes end to end with at least two paid calls.
 * {@code POST /api/v1/runs} runs the real four agents over the scripted model; both tool calls pay
 * the real-socket seller through the x402 client (each EIP-3009 signature verified by the seller).
 */
class ResearchRunEndToEndTests extends AgentRunTestSupport {

    @Autowired
    private JsonMapper json;

    private UUID runTwoPaidCalls() {
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(SUMMARY, summaryArgs("THYAO")),
                Reply.toolCall(ASK, askArgs("THYAO", "How large is the fuel hedge?")),
                Reply.text("THYAO hedged fuel (kap:1001:0001); see also kap:1002:0003."),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001", "kap:9999:0001", "not-an-id")));
        return runId(startRun("What did THYAO disclose about fuel costs?", null));
    }

    @Test
    void aResearchRunCompletesWithTwoSettledPaymentsAndACitedReport() {
        UUID runId = runTwoPaidCalls();
        List<RunEvent> events = awaitTerminal(runId);

        // the run's lifecycle, in order
        assertThat(types(events).getFirst()).isEqualTo(RunEventType.RUN_STARTED);
        assertThat(types(events).getLast()).isEqualTo(RunEventType.RUN_COMPLETED);
        assertThat(types(events))
                .containsSubsequence(
                        RunEventType.RUN_STARTED,
                        RunEventType.STEP_STARTED,
                        RunEventType.MODEL_CALL_COMPLETED,
                        RunEventType.PLAN_CREATED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.STEP_STARTED,
                        RunEventType.TOOL_CALL_REQUESTED,
                        RunEventType.PAYMENT_SETTLED,
                        RunEventType.TOOL_CALL_COMPLETED,
                        RunEventType.TOOL_CALL_REQUESTED,
                        RunEventType.PAYMENT_SETTLED,
                        RunEventType.TOOL_CALL_COMPLETED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.RUN_COMPLETED);
        assertThat(steps(events, RunEventType.STEP_STARTED))
                .containsExactly(AgentStep.PLANNER, AgentStep.RESEARCHER, AgentStep.RISK, AgentStep.SYNTHESIS);
        assertThat(steps(events, RunEventType.STEP_COMPLETED))
                .containsExactly(AgentStep.PLANNER, AgentStep.RESEARCHER, AgentStep.RISK, AgentStep.SYNTHESIS);
        assertThat(events).extracting(RunEvent::seq).isSorted().doesNotHaveDuplicates();
        assertThat(data(events, RunEventData.PlanCreated.class))
                .containsExactly(
                        new RunEventData.PlanCreated(List.of("THYAO"), List.of("Summarise recent disclosures")));
        assertThat(data(events, RunEventData.ToolCallRequested.class))
                .extracting(RunEventData.ToolCallRequested::tool)
                .containsExactly(SUMMARY, ASK);
        assertThat(data(events, RunEventData.ToolCallCompleted.class))
                .containsExactly(
                        new RunEventData.ToolCallCompleted(SUMMARY, true, 2),
                        new RunEventData.ToolCallCompleted(ASK, true, 2));
        // six round trips: planner, researcher x3 (two tool calls), risk, synthesis
        assertThat(data(events, RunEventData.ModelCallCompleted.class))
                .extracting(RunEventData.ModelCallCompleted::step)
                .containsExactly(
                        AgentStep.PLANNER,
                        AgentStep.RESEARCHER,
                        AgentStep.RESEARCHER,
                        AgentStep.RESEARCHER,
                        AgentStep.RISK,
                        AgentStep.SYNTHESIS);

        // two real payments: signed once each, verified and settled by the seller
        List<RunEventData.PaymentSettled> settled = data(events, RunEventData.PaymentSettled.class);
        assertThat(settled).hasSize(2).allSatisfy(p -> {
            assertThat(p.amount()).isEqualTo(Money.usdc(PRICE));
            assertThat(p.txHash()).matches("0x[0-9a-f]{64}");
        });
        assertThat(signer.calls()).isEqualTo(2);
        assertThat(seller.invalidSignatures()).isZero();
        assertThat(seller.paidRequestLines())
                .containsExactly("GET /v1/disclosures/THYAO/summary", "POST /v1/disclosures/THYAO/questions");
        assertThat(intentsWithStatus(runId, "SETTLED")).isEqualTo(2);

        // the report: citations only for ids in the evidence, rebuilt by code from the evidence
        RunEventData.RunCompleted completed =
                (RunEventData.RunCompleted) events.getLast().data();
        assertThat(completed.report().answer()).endsWith("Research summary, not investment advice.");
        assertThat(completed.report().citations())
                .containsExactly(new RunEventData.Citation(
                        "kap:1001:0001", "https://www.kap.org.tr/tr/Bildirim/1001", "Annual report"));

        // the cost: committed payments + the LLM cost the router recorded, the same everywhere
        RunSummary summary = summary(runId);
        assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(summary.committed()).isEqualTo(Money.usdc(2 * PRICE)).isEqualTo(settledOf(events));
        assertThat(summary.reserved()).isEqualTo(Money.usdc(0));
        Money llm = llmCostOf(events);
        assertThat(llm.atomicUnits()).isPositive();
        RunCost expected = RunCost.of(Money.usdc(2 * PRICE), llm);
        assertThat(completed.cost()).isEqualTo(expected);
        assertThat(summary.cost()).isEqualTo(expected);
        assertThat(summary.report()).isEqualTo(completed.report());
        assertThat(expected.totalUsd().atomicUnits()).isEqualTo(2 * PRICE + llm.atomicUnits());
    }

    @Test
    void theSseStreamDeliversExactlyTheEventsOfTheJsonExport() {
        UUID runId = runTwoPaidCalls();
        List<RunEvent> events = awaitTerminal(runId);

        List<Sse> streamed = stream(runId);
        List<JsonNode> exported = new ArrayList<>();
        json.readTree(export(runId)).forEach(exported::add);

        assertThat(streamed)
                .extracting(Sse::id)
                .containsExactlyElementsOf(
                        events.stream().map(e -> Integer.toString(e.seq())).toList());
        assertThat(streamed)
                .extracting(Sse::event)
                .containsExactlyElementsOf(
                        events.stream().map(e -> e.type().name()).toList());
        assertThat(streamed.stream().map(s -> json.readTree(s.data())).toList()).isEqualTo(exported);
        assertThat(exported).hasSize(events.size());
        // nothing secret in the export: no idempotency key, nonce or signature material
        String raw = export(runId);
        assertThat(raw)
                .doesNotContainIgnoringCase("idempotency")
                .doesNotContainIgnoringCase("nonce")
                .doesNotContainIgnoringCase("signature")
                .doesNotContain("dropped by the sanitiser")
                .doesNotContain("evil.example");
    }

    @Test
    void theSellerSawOnlyTheCatalogueAndTheTwoPaidPaths() {
        UUID runId = runTwoPaidCalls();
        awaitTerminal(runId);

        assertThat(seller.requests())
                .extracting(FakeSeller.SeenRequest::path)
                .allMatch(p -> p.equals("/v1/tickers") || p.startsWith("/v1/disclosures/THYAO/"));
        assertThat(seller.redirectTargetRequests()).isZero();
    }

    private static List<AgentStep> steps(List<RunEvent> events, RunEventType type) {
        return events.stream()
                .filter(e -> e.type() == type)
                .map(e -> ((RunEventData.StepChanged) e.data()).step())
                .toList();
    }
}
