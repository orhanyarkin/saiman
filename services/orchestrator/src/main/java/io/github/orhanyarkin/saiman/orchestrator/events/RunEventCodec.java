package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON for run events. Every {@link RunEventType} maps to exactly one {@link RunEventData} payload
 * class (a total mapping, checked at class load and by a test); the payload is stored as-is in
 * {@code run_event.payload} and the SSE/export envelope is the one documented in {@code
 * docs/events/agent.run-step.v1.md}. Money serialises as {@code {atomicUnits, asset, decimals}}.
 */
@Component
public class RunEventCodec {

    private static final Map<RunEventType, Class<? extends RunEventData>> PAYLOADS = payloads();

    private final JsonMapper json;

    public RunEventCodec(JsonMapper json) {
        // Idempotent if Boot's mapper already carries the mixin; makes the codec correct on its own.
        this.json = json.rebuild()
                .addMixIn(io.github.orhanyarkin.saiman.shared.money.Money.class, MoneyJsonMixin.class)
                .build();
    }

    /** The payload class of a type; total over {@link RunEventType}. */
    public static Class<? extends RunEventData> payloadClass(RunEventType type) {
        Class<? extends RunEventData> payload = PAYLOADS.get(type);
        if (payload == null) {
            throw new IllegalStateException("no payload class for " + type);
        }
        return payload;
    }

    /** The whole mapping, for tests and documentation. */
    public static Map<RunEventType, Class<? extends RunEventData>> mapping() {
        return Collections.unmodifiableMap(PAYLOADS);
    }

    /**
     * Serialises a payload.
     *
     * @throws IllegalArgumentException if {@code data} is not the payload class of {@code type}
     */
    public String encodePayload(RunEventType type, RunEventData data) {
        requireMatches(type, data);
        return json.writeValueAsString(data);
    }

    public RunEventData decodePayload(RunEventType type, String payload) {
        return json.readValue(payload, payloadClass(type));
    }

    /**
     * The envelope as sent over SSE and returned by the export endpoint: {@code eventId, runId, seq,
     * type, occurredAt, data}.
     */
    public String encodeEnvelope(RunEvent event) {
        requireMatches(event.type(), event.data());
        JsonNode data = json.valueToTree(event.data());
        return json.writeValueAsString(
                new Envelope(event.eventId(), event.runId(), event.seq(), event.type(), event.occurredAt(), data));
    }

    private static void requireMatches(RunEventType type, RunEventData data) {
        if (!payloadClass(type).isInstance(data)) {
            throw new IllegalArgumentException(
                    "payload " + data.getClass().getSimpleName() + " does not belong to " + type);
        }
    }

    private static Map<RunEventType, Class<? extends RunEventData>> payloads() {
        EnumMap<RunEventType, Class<? extends RunEventData>> map = new EnumMap<>(RunEventType.class);
        for (RunEventType type : RunEventType.values()) {
            // A switch with no default: a new RunEventType fails compilation here until it is mapped.
            Class<? extends RunEventData> payload = switch (type) {
                case RUN_STARTED -> RunEventData.RunStarted.class;
                case STEP_STARTED, STEP_COMPLETED -> RunEventData.StepChanged.class;
                case PLAN_CREATED -> RunEventData.PlanCreated.class;
                case TOOL_CALL_REQUESTED -> RunEventData.ToolCallRequested.class;
                case PAYMENT_APPROVAL_REQUIRED -> RunEventData.PaymentApprovalRequired.class;
                case PAYMENT_APPROVAL_DECIDED -> RunEventData.PaymentApprovalDecided.class;
                case PAYMENT_DENIED -> RunEventData.PaymentDenied.class;
                case PAYMENT_SETTLED -> RunEventData.PaymentSettled.class;
                case PAYMENT_AMBIGUOUS -> RunEventData.PaymentAmbiguous.class;
                case TOOL_CALL_COMPLETED -> RunEventData.ToolCallCompleted.class;
                case MODEL_CALL_COMPLETED -> RunEventData.ModelCallCompleted.class;
                case RUN_COMPLETED -> RunEventData.RunCompleted.class;
                case RUN_FAILED -> RunEventData.RunFailed.class;
            };
            map.put(type, payload);
        }
        return map;
    }

    /** The wire envelope of {@code agent.run-step.v1}. */
    record Envelope(String eventId, UUID runId, int seq, RunEventType type, Instant occurredAt, JsonNode data) {}
}
