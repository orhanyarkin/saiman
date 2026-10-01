package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApiRequestGuardFilter;
import io.github.orhanyarkin.saiman.orchestrator.run.RunSummary;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Shared setup for the acceptance tests that run the <b>real</b> agents end to end: {@code POST
 * /api/v1/runs} -> {@code RunService} -> {@code AgentPipeline} (Spring AI {@code ChatClient} and
 * tool loop over the real model router, whose only fake is the {@code ChatModel}) -> {@code
 * PaidToolGateway} -> the x402 {@code RestClient} with the real {@code BudgetSpendGuard} -> the
 * real-socket {@link FakeSeller}, which verifies every EIP-3009 signature. Nothing leaves loopback.
 *
 * <p>No {@code ScriptedPipeline} here (that is {@link RunTestSupport}'s context). Limits on top of
 * {@link SpendTestSupport}'s: 12 tool calls per run, so the adversarial models can try 10+ calls.
 */
@TestPropertySource(
        properties = "saiman.orchestrator.spend.max-tool-calls-per-run=" + AgentRunTestSupport.MAX_TOOL_CALLS)
public abstract class AgentRunTestSupport extends RunHarness {

    protected static final int MAX_TOOL_CALLS = 12;
    protected static final long PRICE = 10_000;

    protected static final String SUMMARY = "disclosureSummary";
    protected static final String ASK = "askDisclosures";

    protected static final String PLAN = "{\"tickers\":[\"THYAO\"],\"tasks\":[\"Summarise recent disclosures\"]}";
    protected static final String RISKS =
            "{\"risks\":[{\"title\":\"Fuel cost exposure\",\"severity\":\"medium\",\"citedChunkIds\":[\"kap:1001:0001\"]}]}";

    /** What a settled seller call answers: one citation with a KAP URL, one with a foreign URL. */
    protected static final String EVIDENCE_BODY = "{\"answer\":\"THYAO hedged part of its fuel needs.\","
            + "\"citations\":[{\"chunkId\":\"kap:1001:0001\",\"sourceUrl\":\"https://www.kap.org.tr/tr/Bildirim/1001\","
            + "\"title\":\"Annual report\"},{\"chunkId\":\"kap:1002:0003\",\"sourceUrl\":\"https://evil.example/x\","
            + "\"title\":\"Q3 report\"}],\"internal\":\"dropped by the sanitiser\"}";

    @BeforeEach
    void sellerAnswersWithEvidence() {
        seller.paidBody(EVIDENCE_BODY);
    }

    protected static String synthesis(String... ids) {
        return "{\"answer\":\"THYAO reported fuel hedging. Research summary, not investment advice.\","
                + "\"citedChunkIds\":["
                + String.join(
                        ",", List.of(ids).stream().map(i -> "\"" + i + "\"").toList())
                + "]}";
    }

    protected static String summaryArgs(String ticker) {
        return "{\"ticker\":\"" + ticker + "\"}";
    }

    protected static String askArgs(String ticker, String question) {
        return "{\"ticker\":\"" + ticker + "\",\"question\":\"" + question + "\"}";
    }

    /** The pipeline step a prompt belongs to, from the step's fixed prompt text. */
    protected enum Step {
        PLANNER,
        RESEARCHER,
        RISK,
        SYNTHESIS;

        static Step of(Prompt prompt) {
            String user = prompt.getUserMessage().getText();
            if (user.startsWith("Task: plan the research")) {
                return PLANNER;
            }
            if (user.startsWith("Task: gather evidence")) {
                return RESEARCHER;
            }
            if (user.startsWith("Task: list the main risks")) {
                return RISK;
            }
            if (user.startsWith("Task: write the final research summary")) {
                return SYNTHESIS;
            }
            throw new AssertionError("unknown step prompt");
        }
    }

    /**
     * A model that answers by step: the plan, then {@code researcher.apply(round)} for each
     * researcher round trip (0, 1, ...), then the risks and the synthesis. Used with {@code
     * model.otherwise(..)} for models that decide on their own how many tool calls to make.
     */
    protected static Function<Prompt, Reply> byStep(
            String plan, IntFunction<Reply> researcher, String risks, String synthesis) {
        AtomicInteger rounds = new AtomicInteger();
        return prompt -> switch (Step.of(prompt)) {
            case PLANNER -> Reply.text(plan);
            case RESEARCHER -> researcher.apply(rounds.getAndIncrement());
            case RISK -> Reply.text(risks);
            case SYNTHESIS -> Reply.text(synthesis);
        };
    }

    protected RunSummary summary(UUID runId) {
        return runService.summary(runId).orElseThrow();
    }

    protected static <T extends RunEventData> List<T> data(List<RunEvent> events, Class<T> type) {
        return events.stream()
                .map(RunEvent::data)
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    protected static long count(List<RunEvent> events, RunEventType type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    /** Sum of the LLM cost the run's {@code MODEL_CALL_COMPLETED} events carry. */
    protected static Money llmCostOf(List<RunEvent> events) {
        return Money.usdMicros(data(events, RunEventData.ModelCallCompleted.class).stream()
                .mapToLong(c -> c.costUsd().atomicUnits())
                .sum());
    }

    /** Sum of the amounts the run's {@code PAYMENT_SETTLED} events carry. */
    protected static Money settledOf(List<RunEvent> events) {
        return Money.usdc(data(events, RunEventData.PaymentSettled.class).stream()
                .mapToLong(p -> p.amount().atomicUnits())
                .sum());
    }

    /** Waits until the run has emitted an event of {@code type} and returns the first one. */
    protected RunEvent awaitEvent(UUID runId, RunEventType type) {
        await().atMost(Duration.ofSeconds(20))
                .until(() -> eventLog.readAfter(runId, 0).stream().anyMatch(e -> e.type() == type));
        return eventLog.readAfter(runId, 0).stream()
                .filter(e -> e.type() == type)
                .findFirst()
                .orElseThrow();
    }

    /** {@code POST /approvals/{id}} as the UI sends it: JSON, the CSRF header, the test client's Host. */
    protected RestTestClient.ResponseSpec decide(UUID runId, UUID approvalId, String decision) {
        return http.post()
                .uri("/api/v1/runs/{runId}/approvals/{approvalId}", runId, approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"" + decision + "\"}")
                .exchange();
    }

    /** The complete SSE stream of a finished run (replayed from the database, then closed). */
    protected List<Sse> stream(UUID runId) {
        String raw = http.get()
                .uri("/api/v1/runs/{id}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(raw).isNotNull();
        return parse(raw);
    }

    /** The JSON export of a run's events ({@code Accept: application/json}). */
    protected String export(UUID runId) {
        String body = http.get()
                .uri("/api/v1/runs/{id}/events", runId)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    /** One server-sent event. */
    public record Sse(String id, String event, String data) {}

    private static List<Sse> parse(String raw) {
        List<Sse> events = new ArrayList<>();
        for (String block : raw.split("\n\n")) {
            String id = null;
            String event = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
            if (id != null) {
                events.add(new Sse(id, event, data.toString()));
            }
        }
        Set<String> ids = new HashSet<>();
        events.forEach(
                e -> assertThat(ids.add(e.id())).as("duplicate id " + e.id()).isTrue());
        return events;
    }
}
