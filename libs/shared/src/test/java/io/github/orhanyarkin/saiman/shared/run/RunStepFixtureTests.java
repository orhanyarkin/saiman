package io.github.orhanyarkin.saiman.shared.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunEventData.Citation;
import io.github.orhanyarkin.saiman.shared.run.RunEventData.Report;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden fixtures for the SSE/export envelope of {@code agent.run-step.v1}, one file per {@link RunEventType}
 * under {@code fixtures/events/agent.run-step.v1/}. The web client's runtime guard and the fixture server are tested
 * against the same files (ADR-0022). A new {@link RunEventType} fails this test until it has a sample.
 *
 * <p>Rewrite the files after a deliberate contract change with {@code SAIMAN_FIXTURES_UPDATE=1 ./gradlew
 * :libs:shared:test}; the source tree path is resolved from the working directory Gradle gives the test JVM.
 */
class RunStepFixtureTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID RUN = UUID.fromString("6ad4354c-8e79-4b49-b5d9-d45eb9689b41");
    private static final UUID INTENT = UUID.fromString("75f93933-72b5-4604-ad2c-063e3f4e6ed6");
    private static final UUID APPROVAL = UUID.fromString("0b5c6a8e-3c1d-4b1e-9f0a-2f6d7c1e9a01");
    private static final String PAY_TO = "0x1111111111111111111111111111111111111111";
    private static final String RESOURCE = "http://seller-api:8081/v1/disclosures/THYAO/questions";
    private static final String TX = "0x68592d03715426a9a3aea48c4f50df430e6e10ad46962d9ab0c7c532a73bb090";
    private static final Path DIR = Path.of("src/test/resources/fixtures/events/agent.run-step.v1");

    private static Map<RunEventType, RunEventData> samples() {
        Money usdc = new Money(20_000, "USDC", 6);
        RunCost cost = RunCost.of(new Money(30_000, "USDC", 6), Money.usdMicros(6_923));
        var map = new EnumMap<RunEventType, RunEventData>(RunEventType.class);
        map.put(
                RunEventType.RUN_STARTED,
                new RunEventData.RunStarted("THYAO son özel durum açıklamaları neler?", new Money(50_000, "USDC", 6)));
        map.put(RunEventType.STEP_STARTED, new RunEventData.StepChanged(AgentStep.PLANNER));
        map.put(RunEventType.STEP_COMPLETED, new RunEventData.StepChanged(AgentStep.PLANNER));
        map.put(
                RunEventType.PLAN_CREATED,
                new RunEventData.PlanCreated(List.of("THYAO"), List.of("Summarise the latest disclosures")));
        map.put(
                RunEventType.TOOL_CALL_REQUESTED,
                new RunEventData.ToolCallRequested("ask_disclosures", "ticker=THYAO question=\"son açıklamalar\""));
        map.put(
                RunEventType.PAYMENT_APPROVAL_REQUIRED,
                new RunEventData.PaymentApprovalRequired(
                        APPROVAL, INTENT, usdc, PAY_TO, RESOURCE, Instant.parse("2026-10-01T10:05:00Z")));
        map.put(RunEventType.PAYMENT_APPROVAL_DECIDED, new RunEventData.PaymentApprovalDecided(APPROVAL, "APPROVED"));
        map.put(RunEventType.PAYMENT_DENIED, new RunEventData.PaymentDenied(DenyReason.RUN_BUDGET, usdc));
        map.put(RunEventType.PAYMENT_SETTLED, new RunEventData.PaymentSettled(INTENT, usdc, TX));
        map.put(RunEventType.PAYMENT_AMBIGUOUS, new RunEventData.PaymentAmbiguous(INTENT, usdc));
        map.put(RunEventType.TOOL_CALL_COMPLETED, new RunEventData.ToolCallCompleted("ask_disclosures", true, 3));
        map.put(
                RunEventType.MODEL_CALL_COMPLETED,
                new RunEventData.ModelCallCompleted(
                        AgentStep.SYNTHESIS, "tier1", "gpt-5.6-luna", 1_200, 340, Money.usdMicros(1_850)));
        map.put(
                RunEventType.RUN_COMPLETED,
                new RunEventData.RunCompleted(
                        new Report(
                                "Kurumsal yönetim uyum raporuna göre ...",
                                List.of(new Citation(
                                        "kap:1118495:0000",
                                        "https://www.kap.org.tr/tr/Bildirim/1118495",
                                        "Özel Durum Açıklaması"))),
                        cost));
        map.put(RunEventType.RUN_FAILED, new RunEventData.RunFailed("RUN_DEADLINE", cost));
        return map;
    }

    private static String envelope(int seq, RunEventType type, RunEventData data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", RUN + ":" + seq);
        envelope.put("runId", RUN.toString());
        envelope.put("seq", seq);
        envelope.put("type", type.name());
        envelope.put("occurredAt", Instant.parse("2026-10-01T10:00:00Z").plusSeconds(seq));
        envelope.put("data", JSON.valueToTree(data));
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(envelope) + "\n";
    }

    @Test
    void everyEventTypeHasAGoldenEnvelopeThatMatchesTheContract() throws IOException {
        Map<RunEventType, RunEventData> samples = samples();
        assertThat(samples.keySet()).containsExactlyInAnyOrder(RunEventType.values());
        boolean update = "1".equals(System.getenv("SAIMAN_FIXTURES_UPDATE"));
        if (update) {
            Files.createDirectories(DIR);
        }
        int seq = 1;
        for (RunEventType type : RunEventType.values()) {
            String actual = envelope(seq++, type, samples.get(type));
            Path file = DIR.resolve(type.name() + ".json");
            if (update) {
                Files.writeString(file, actual, StandardCharsets.UTF_8);
            }
            assertThat(file)
                    .as("fixture for %s (run with SAIMAN_FIXTURES_UPDATE=1 to create it)", type)
                    .exists();
            JsonNode expected = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(JSON.readTree(actual)).as(type.name()).isEqualTo(expected);
        }
    }
}
