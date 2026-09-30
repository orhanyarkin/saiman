package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApiRequestGuardFilter;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventAppender;
import io.github.orhanyarkin.saiman.orchestrator.run.RunService;
import io.github.orhanyarkin.saiman.orchestrator.run.RunTestAccess;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Shared setup for the run, event and tool tests: everything {@link SpendTestSupport} has, plus a
 * {@link ScriptedPipeline}, tracing into an in-memory exporter, a short SSE heartbeat and a short
 * approval timeout. All these tests share one Spring context.
 */
@TestPropertySource(
        properties = {"saiman.orchestrator.events.heartbeat=200ms", "saiman.orchestrator.spend.approval-timeout=4s"})
@AutoConfigureTracing
@Import(RunTestSupport.RunTestConfiguration.class)
public abstract class RunTestSupport extends SpendTestSupport {

    // Not a bean: Boot would wrap an exporter bean in a batch processor (see PingTracingTests).
    protected static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @Autowired
    protected RestTestClient http;

    @Autowired
    protected ScriptedPipeline pipeline;

    @Autowired
    protected RunService runService;

    @Autowired
    protected RunEventAppender eventLog;

    @BeforeEach
    void resetRuns() {
        pipeline.reset();
        SPANS.reset();
    }

    @AfterEach
    void awaitIdleRuns() {
        // A run still executing would write into the next test's truncated tables.
        await().atMost(Duration.ofSeconds(20)).until(() -> RunTestAccess.activeRuns(runService) == 0);
    }

    /** POSTs a run with the guard headers and returns the 202 body. */
    protected Map<String, Object> startRun(String question, Long budgetAtomic) {
        String body = budgetAtomic == null
                ? "{\"question\":" + json(question) + "}"
                : "{\"question\":" + json(question) + ",\"budgetAtomic\":" + budgetAtomic + "}";
        Map<String, Object> response = postRun(body)
                .expectStatus()
                .isAccepted()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    protected RestTestClient.ResponseSpec postRun(String json) {
        return http.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body(json)
                .exchange();
    }

    protected static UUID runId(Map<String, Object> started) {
        return UUID.fromString((String) started.get("runId"));
    }

    /** Waits for the run's terminal event and returns every event of the run. */
    protected List<RunEvent> awaitTerminal(UUID runId) {
        await().atMost(Duration.ofSeconds(20))
                .until(() -> eventLog.readAfter(runId, 0).stream()
                        .anyMatch(e -> e.type().terminal()));
        await().atMost(Duration.ofSeconds(20)).until(() -> RunTestAccess.activeRuns(runService) == 0);
        return eventLog.readAfter(runId, 0);
    }

    protected static List<RunEventType> types(List<RunEvent> events) {
        List<RunEventType> types = new ArrayList<>();
        events.forEach(e -> types.add(e.type()));
        return types;
    }

    protected static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(cp -> {
            if (cp == '"' || cp == '\\') {
                out.append('\\').appendCodePoint(cp);
            } else if (cp < 0x20
                    || (cp >= 0x7f && cp <= 0x9f)
                    || cp > 0xffff
                    || Character.getType(cp) == Character.FORMAT) {
                for (char c : Character.toChars(cp)) {
                    out.append(String.format("\\u%04x", (int) c));
                }
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.append('"').toString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RunTestConfiguration {

        @Bean
        ScriptedPipeline scriptedPipeline() {
            return new ScriptedPipeline();
        }

        @Bean
        SpanProcessor inMemorySpanProcessor() {
            return SimpleSpanProcessor.create(SPANS);
        }
    }
}
