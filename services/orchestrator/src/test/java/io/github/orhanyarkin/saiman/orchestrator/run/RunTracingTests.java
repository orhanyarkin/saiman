package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Acceptance 4: the root span's cost attributes equal the persisted totals (in-memory exporter). */
class RunTracingTests extends RunTestSupport {

    private static AttributeKey<String> key(String name) {
        return AttributeKey.stringKey(name);
    }

    @Test
    void rootSpanCostAttributesEqualTheDatabaseTotals() {
        pipeline.script(ctx -> {
            assertThat(ctx.tools().call("disclosureSummary", "{\"ticker\":\"THYAO\"}"))
                    .startsWith("<tool_data>");
            assertThat(ctx.tools().call("askDisclosures", "{\"ticker\":\"ASELS\",\"question\":\"What changed?\"}"))
                    .startsWith("<tool_data>");
            ctx.modelCalls()
                    .record(new RunEventData.ModelCallCompleted(
                            AgentStep.RESEARCHER, "TIER1", "test-model", 100, 50, Money.usdMicros(1_234)));
            return RunOutcome.succeeded(new RunEventData.Report("done", List.of()));
        });
        Map<String, Object> started = startRun("What did THYAO disclose?", null);
        UUID runId = runId(started);
        List<RunEvent> events = awaitTerminal(runId);

        RunSummary summary = runService.summary(runId).orElseThrow();
        assertThat(summary.committed()).isEqualTo(Money.usdc(20_000));
        RunCost expected = RunCost.of(Money.usdc(20_000), Money.usdMicros(1_234));
        assertThat(summary.cost()).isEqualTo(expected);
        assertThat(events.getLast().data())
                .isInstanceOfSatisfying(
                        RunEventData.RunCompleted.class,
                        c -> assertThat(c.cost()).isEqualTo(expected));

        String traceId = (String) started.get("traceId");
        assertThat(traceId).isNotBlank().isEqualTo(summary.traceId());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            SpanData root = SPANS.getFinishedSpanItems().stream()
                    .filter(s -> runId.toString().equals(s.getAttributes().get(key("saiman.run.id"))))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no saiman.run span"));
            assertThat(root.getTraceId()).isEqualTo(traceId);
            assertThat(root.getParentSpanContext().isValid())
                    .as("root span has no parent")
                    .isFalse();
            assertThat(root.getAttributes().get(key("saiman.run.cost.payments_usdc_atomic")))
                    .isEqualTo(Long.toString(summary.committed().atomicUnits()));
            assertThat(root.getAttributes().get(key("saiman.run.cost.llm_usd_micros")))
                    .isEqualTo(Long.toString(summary.cost().llmUsd().atomicUnits()));
            assertThat(root.getAttributes().get(key("saiman.run.cost.total_usd_micros")))
                    .isEqualTo("21234");
            List<SpanData> payments = SPANS.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals("saiman.run.payment"))
                    .filter(s -> s.getTraceId().equals(traceId))
                    .toList();
            assertThat(payments)
                    .hasSize(2)
                    .allSatisfy(p -> assertThat(p.getParentSpanId()).isEqualTo(root.getSpanId()));
        });
    }
}
