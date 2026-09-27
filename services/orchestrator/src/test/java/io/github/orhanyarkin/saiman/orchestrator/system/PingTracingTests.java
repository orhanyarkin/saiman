package io.github.orhanyarkin.saiman.orchestrator.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.TestcontainersConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The automated half of the M0 trace acceptance (ADR-0006): a caller's {@code traceparent} is
 * continued by the orchestrator's HTTP server span, and the JDBC {@code query} span for
 * {@code select now()} sits under that server span in the same trace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PingTracingTests {

    // Not a bean: Boot wraps every SpanExporter bean in a BatchSpanProcessor, which would export
    // each span a second time. Only the synchronous processor below feeds this exporter.
    private static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

    private static final AttributeKey<String> JDBC_QUERY = AttributeKey.stringKey("jdbc.query[0]");

    @Autowired
    private RestTestClient client;

    @BeforeEach
    void resetExporter() {
        EXPORTER.reset();
    }

    @Test
    void serverSpanContinuesCallerTraceAndQuerySpanIsItsDescendant() {
        String traceId = randomHex(16);
        String callerSpanId = randomHex(8);

        PingResponse body = client.get()
                .uri("/api/v1/ping")
                .header("traceparent", "00-" + traceId + "-" + callerSpanId + "-01")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PingResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.service()).isEqualTo("orchestrator");
        assertThat(body.traceId()).isEqualTo(traceId);

        // The server span ends after the response is written, so poll briefly.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SpanData> spans = EXPORTER.getFinishedSpanItems().stream()
                    .filter(span -> span.getTraceId().equals(traceId))
                    .toList();
            Map<String, SpanData> bySpanId =
                    spans.stream().collect(Collectors.toMap(SpanData::getSpanId, Function.identity()));

            List<SpanData> servers = spans.stream()
                    .filter(span -> span.getKind() == SpanKind.SERVER)
                    .toList();
            assertThat(servers).as("HTTP server spans").hasSize(1);
            SpanData server = servers.getFirst();
            assertThat(server.getParentSpanId()).as("server span's parent").isEqualTo(callerSpanId);

            // datasource-micrometer's span names are "connection" / "query" / "result-set";
            // "jdbc.*" is the observation name and the attribute-key prefix.
            SpanData query = spans.stream()
                    .filter(span -> span.getName().equals("query"))
                    .filter(span ->
                            String.valueOf(span.getAttributes().get(JDBC_QUERY)).contains("select now()"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no JDBC query span for select now()"));
            assertThat(isDescendant(query, server, bySpanId))
                    .as("query span is under the server span")
                    .isTrue();
        });
    }

    private static boolean isDescendant(SpanData span, SpanData ancestor, Map<String, SpanData> bySpanId) {
        @Nullable SpanData current = span;
        while (current != null) {
            if (current.getParentSpanId().equals(ancestor.getSpanId())) {
                return true;
            }
            current = bySpanId.get(current.getParentSpanId());
        }
        return false;
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryTracingConfiguration {

        @Bean
        SpanProcessor inMemorySpanProcessor() {
            // Synchronous: a span is exported as soon as it ends.
            return SimpleSpanProcessor.create(EXPORTER);
        }
    }
}
