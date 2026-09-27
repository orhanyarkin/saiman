package io.github.orhanyarkin.saiman.orchestrator.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.AbstractIntegrationTest;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the full local trace path from an incoming HTTP request through the orchestrator into
 * Postgres: a fixed {@code traceparent} on the request must show up as the {@code traceId} in the
 * response, and both the HTTP server span and a JDBC query span must be exported under that trace
 * id (ADR-0006).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing
class PingTracingIT extends AbstractIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Autowired
    private InMemorySpanExporter spanExporter;

    @Test
    void pingPropagatesTraceIdAndExportsHttpAndJdbcSpans() {
        ResponseEntity<PingResponse> response = restClient()
                .get()
                .uri("/api/v1/ping")
                .header("traceparent", TRACEPARENT)
                .retrieve()
                .toEntity(PingResponse.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        PingResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.service()).isEqualTo("orchestrator");
        assertThat(body.traceId()).isEqualTo(TRACE_ID);

        // The HTTP server span ends (and is exported) after the response is written, so poll
        // briefly rather than asserting immediately.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SpanData> spansForTrace = spanExporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getTraceId().equals(TRACE_ID))
                    .toList();

            assertThat(spansForTrace)
                    .as("spans exported under traceparent's trace id")
                    .isNotEmpty();
            assertThat(spansForTrace).as("HTTP server span").anyMatch(span -> span.getKind() == SpanKind.SERVER);
            // datasource-micrometer names its spans "connection" / "query" / "result-set", not
            // "jdbc.*"; the "jdbc.*" prefix shows up in the span attributes instead
            // (jdbc.query[0], jdbc.datasource.name, ...).
            assertThat(spansForTrace)
                    .as("JDBC span from datasource-micrometer (jdbc.* attributes)")
                    .anyMatch(span -> span.getAttributes().asMap().keySet().stream()
                            .anyMatch(key -> key.getKey().startsWith("jdbc.")));
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TracingTestConfig {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SpanProcessor inMemorySpanProcessor(InMemorySpanExporter inMemorySpanExporter) {
            // Simple (synchronous) processor: spans are exported as soon as they end, so the
            // test doesn't need to wait out a batch export interval.
            return SimpleSpanProcessor.create(inMemorySpanExporter);
        }
    }
}
