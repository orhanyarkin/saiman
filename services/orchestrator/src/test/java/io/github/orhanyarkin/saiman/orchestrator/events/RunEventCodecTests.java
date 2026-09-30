package io.github.orhanyarkin.saiman.orchestrator.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The type <-> payload mapping is total, and every payload survives a JSON round trip. */
class RunEventCodecTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Money USDC = Money.usdc(10_000);
    private final RunEventCodec codec = new RunEventCodec(JSON);

    @Test
    void everyTypeHasExactlyOnePayloadClass() {
        Map<RunEventType, Class<? extends RunEventData>> mapping = RunEventCodec.mapping();
        assertThat(mapping.keySet()).containsExactlyInAnyOrder(RunEventType.values());
        for (RunEventType type : RunEventType.values()) {
            assertThat(RunEventCodec.payloadClass(type)).isEqualTo(mapping.get(type));
        }
    }

    @Test
    void everyPayloadClassBelongsToSomeType() {
        Set<Class<?>> used = new HashSet<>(RunEventCodec.mapping().values());
        assertThat(used)
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(RunEventData.class.getPermittedSubclasses()));
    }

    @Test
    void everyTypeRoundTrips() {
        Map<RunEventType, RunEventData> samples = samples();
        assertThat(samples.keySet()).containsExactlyInAnyOrder(RunEventType.values());
        samples.forEach((type, data) -> {
            String json = codec.encodePayload(type, data);
            assertThat(codec.decodePayload(type, json)).as(type.name()).isEqualTo(data);
        });
    }

    @Test
    void moneyIsAtomicUnitsAssetAndDecimalsOnly() {
        JsonNode node = JSON.readTree(
                codec.encodePayload(RunEventType.PAYMENT_SETTLED, new RunEventData.PaymentSettled(ID, USDC, "0xabc")));
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("paymentIntentId", "amount", "txHash");
        assertThat(fieldNames(node.get("amount"))).containsExactlyInAnyOrder("atomicUnits", "asset", "decimals");
        assertThat(node.get("amount").get("atomicUnits").asLong()).isEqualTo(10_000);
        assertThat(node.get("amount").get("atomicUnits").isIntegralNumber()).isTrue();
    }

    @Test
    void theEnvelopeIsTheDocumentedShape() {
        Instant at = Instant.parse("2026-10-01T10:15:30.123456Z");
        RunEvent event =
                new RunEvent(ID, 3, RunEventType.STEP_STARTED, at, new RunEventData.StepChanged(AgentStep.PLANNER));
        JsonNode node = JSON.readTree(codec.encodeEnvelope(event));
        assertThat(fieldNames(node)).containsExactly("eventId", "runId", "seq", "type", "occurredAt", "data");
        assertThat(node.get("eventId").asString()).isEqualTo(ID + ":3");
        assertThat(node.get("seq").asInt()).isEqualTo(3);
        assertThat(node.get("type").asString()).isEqualTo("STEP_STARTED");
        assertThat(Instant.parse(node.get("occurredAt").asString())).isEqualTo(at);
        assertThat(node.get("data").get("step").asString()).isEqualTo("PLANNER");
    }

    @Test
    void aPayloadOfAnotherTypeIsRefused() {
        assertThatThrownBy(() ->
                        codec.encodePayload(RunEventType.RUN_COMPLETED, new RunEventData.StepChanged(AgentStep.RISK)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }

    static Map<RunEventType, RunEventData> samples() {
        RunCost cost = RunCost.of(USDC, Money.usdMicros(1234));
        RunEventData.Report report = new RunEventData.Report(
                "answer",
                List.of(new RunEventData.Citation("kap:1:0001", "https://www.kap.org.tr/tr/Bildirim/1", "title")));
        Map<RunEventType, RunEventData> samples = new EnumMap<>(RunEventType.class);
        samples.put(RunEventType.RUN_STARTED, new RunEventData.RunStarted("question?", Money.usdc(50_000)));
        samples.put(RunEventType.STEP_STARTED, new RunEventData.StepChanged(AgentStep.PLANNER));
        samples.put(RunEventType.STEP_COMPLETED, new RunEventData.StepChanged(AgentStep.SYNTHESIS));
        samples.put(RunEventType.PLAN_CREATED, new RunEventData.PlanCreated(List.of("THYAO"), List.of("task")));
        samples.put(
                RunEventType.TOOL_CALL_REQUESTED,
                new RunEventData.ToolCallRequested("disclosureSummary", "{\"ticker\":\"THYAO\"}"));
        samples.put(
                RunEventType.PAYMENT_APPROVAL_REQUIRED,
                new RunEventData.PaymentApprovalRequired(
                        ID, ID, USDC, "0xabc", "http://seller/x", Instant.parse("2026-10-01T10:00:00Z")));
        samples.put(RunEventType.PAYMENT_APPROVAL_DECIDED, new RunEventData.PaymentApprovalDecided(ID, "APPROVED"));
        samples.put(RunEventType.PAYMENT_DENIED, new RunEventData.PaymentDenied(DenyReason.RUN_BUDGET, USDC));
        samples.put(RunEventType.PAYMENT_SETTLED, new RunEventData.PaymentSettled(ID, USDC, "0xabc"));
        samples.put(RunEventType.PAYMENT_AMBIGUOUS, new RunEventData.PaymentAmbiguous(ID, USDC));
        samples.put(RunEventType.TOOL_CALL_COMPLETED, new RunEventData.ToolCallCompleted("askDisclosures", true, 2));
        samples.put(
                RunEventType.MODEL_CALL_COMPLETED,
                new RunEventData.ModelCallCompleted(
                        AgentStep.RESEARCHER, "TIER1", "gpt-test", 100, 50, Money.usdMicros(1234)));
        samples.put(RunEventType.RUN_COMPLETED, new RunEventData.RunCompleted(report, cost));
        samples.put(RunEventType.RUN_FAILED, new RunEventData.RunFailed("INTERNAL_ERROR", cost));
        return samples;
    }
}
