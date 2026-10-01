package io.github.orhanyarkin.saiman.orchestrator.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.run.RunSummary;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.AgentRunTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Acceptance 4: per-run USD cost is visible in traces. For a full agent run (real agents, real
 * router, two real x402 payments), the root {@code saiman.run} span carries the three {@code
 * saiman.run.cost.*} attributes, equal to the database totals and to the {@code RUN_COMPLETED}
 * cost, LLM cost included; the step spans and the payment spans hang below it.
 */
class RunCostTracingTests extends AgentRunTestSupport {

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    @Test
    void theRootSpanOfAFullAgentRunCarriesTheDatabaseCostTotals() {
        model.then(
                Reply.text(PLAN).withUsage(2_000, 300),
                Reply.toolCall(SUMMARY, summaryArgs("THYAO")),
                Reply.toolCall(ASK, askArgs("THYAO", "How large is the fuel hedge?")),
                Reply.text("notes kap:1001:0001").withUsage(5_000, 800),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001")).withUsage(4_000, 600));
        Map<String, Object> started = startRun("What did THYAO disclose about fuel costs?", null);
        UUID runId = runId(started);
        List<RunEvent> events = awaitTerminal(runId);

        RunSummary summary = summary(runId);
        RunCost cost = ((RunEventData.RunCompleted) events.getLast().data()).cost();
        assertThat(cost).isEqualTo(summary.cost());
        assertThat(cost.paymentsUsdc()).isEqualTo(Money.usdc(2 * PRICE)).isEqualTo(summary.committed());
        assertThat(cost.llmUsd()).isEqualTo(llmCostOf(events));
        assertThat(cost.llmUsd().atomicUnits()).isPositive();
        Long llmInDb = jdbc.sql("SELECT llm_cost_usd_micros FROM run WHERE id = :id")
                .param("id", runId)
                .query(Long.class)
                .single();
        assertThat(llmInDb).isEqualTo(cost.llmUsd().atomicUnits());

        String traceId = (String) started.get("traceId");
        assertThat(traceId).isNotBlank().isEqualTo(summary.traceId());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SpanData> trace = SPANS.getFinishedSpanItems().stream()
                    .filter(s -> s.getTraceId().equals(traceId))
                    .toList();
            SpanData root = trace.stream()
                    .filter(s -> runId.toString().equals(s.getAttributes().get(key("saiman.run.id"))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no saiman.run span"));
            assertThat(root.getParentSpanContext().isValid())
                    .as("root span has no parent")
                    .isFalse();
            assertThat(root.getAttributes().get(key("saiman.run.cost.payments_usdc_atomic")))
                    .isEqualTo(Long.toString(summary.committed().atomicUnits()));
            assertThat(root.getAttributes().get(key("saiman.run.cost.llm_usd_micros")))
                    .isEqualTo(Long.toString(llmInDb));
            assertThat(root.getAttributes().get(key("saiman.run.cost.total_usd_micros")))
                    .isEqualTo(Long.toString(2 * PRICE + llmInDb))
                    .isEqualTo(Long.toString(cost.totalUsd().atomicUnits()));

            // one span per step, each a direct child of the root
            List<SpanData> steps = trace.stream()
                    .filter(s -> s.getName().startsWith("run-step-"))
                    .toList();
            assertThat(steps)
                    .extracting(SpanData::getName)
                    .containsExactlyInAnyOrder(
                            "run-step-planner", "run-step-researcher", "run-step-risk", "run-step-synthesis");
            assertThat(steps).allSatisfy(s -> assertThat(s.getParentSpanId()).isEqualTo(root.getSpanId()));

            // the two payments happen inside the researcher step (below Spring AI's tool spans)
            SpanData researcher = steps.stream()
                    .filter(s -> s.getName().equals("run-step-researcher"))
                    .findFirst()
                    .orElseThrow();
            Map<String, SpanData> byId =
                    trace.stream().collect(Collectors.toMap(SpanData::getSpanId, Function.identity()));
            List<SpanData> payments = trace.stream()
                    .filter(s -> s.getName().equals("saiman.run.payment"))
                    .toList();
            assertThat(payments).hasSize(2).allSatisfy(p -> {
                assertThat(ancestors(p, byId)).contains(researcher.getSpanId(), root.getSpanId());
                assertThat(p.getAttributes().get(key("saiman.payment.amount_atomic")))
                        .isEqualTo(Long.toString(PRICE));
            });
        });
    }

    @Test
    void aFailedRunsRootSpanCarriesItsFailureCodeAndTheDatabaseCostTotals() {
        // the researcher's answer reports 130000 output tokens: over the 150000 micro-USD scope
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(SUMMARY, summaryArgs("THYAO")),
                Reply.text("notes kap:1001:0001").withUsage(1_000, 130_000),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001")));
        Map<String, Object> started = startRun("What did THYAO disclose about fuel costs?", null);
        UUID runId = runId(started);
        List<RunEvent> events = awaitTerminal(runId);

        RunSummary summary = summary(runId);
        assertThat(summary.failureCode()).isEqualTo("LLM_BUDGET_EXHAUSTED");
        RunCost cost = ((RunEventData.RunFailed) events.getLast().data()).costSoFar();
        assertThat(cost).isEqualTo(summary.cost());
        assertThat(cost.paymentsUsdc()).isEqualTo(Money.usdc(PRICE));
        assertThat(cost.llmUsd().atomicUnits()).isPositive();

        String traceId = (String) started.get("traceId");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            SpanData root = SPANS.getFinishedSpanItems().stream()
                    .filter(s -> s.getTraceId().equals(traceId))
                    .filter(s -> runId.toString().equals(s.getAttributes().get(key("saiman.run.id"))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no saiman.run span"));
            assertThat(root.getAttributes().get(key("saiman.run.failure_code"))).isEqualTo("LLM_BUDGET_EXHAUSTED");
            assertThat(root.getAttributes().get(key("saiman.run.cost.payments_usdc_atomic")))
                    .isEqualTo(Long.toString(cost.paymentsUsdc().atomicUnits()));
            assertThat(root.getAttributes().get(key("saiman.run.cost.llm_usd_micros")))
                    .isEqualTo(Long.toString(cost.llmUsd().atomicUnits()));
            assertThat(root.getAttributes().get(key("saiman.run.cost.total_usd_micros")))
                    .isEqualTo(Long.toString(cost.totalUsd().atomicUnits()));
        });
    }

    private static List<String> ancestors(SpanData span, Map<String, SpanData> byId) {
        List<String> chain = new java.util.ArrayList<>();
        SpanData current = span;
        while (current.getParentSpanContext().isValid()) {
            chain.add(current.getParentSpanId());
            current = byId.get(current.getParentSpanId());
            if (current == null) {
                break;
            }
        }
        return chain;
    }
}
