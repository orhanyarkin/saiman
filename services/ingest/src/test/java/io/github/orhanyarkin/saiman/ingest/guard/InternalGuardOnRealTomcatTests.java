package io.github.orhanyarkin.saiman.ingest.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import io.github.orhanyarkin.saiman.ingest.dlq.DeadLetterService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Crafted request lines over a raw socket to the embedded Tomcat. MockMvc and the HTTP clients
 * normalise paths, which would hide the path-form bypass of the guard.
 */
class InternalGuardOnRealTomcatTests extends IngestIntegrationTests {

    private static final String RETRY = "/internal/v1/admin/retry-dlq";

    @Value("${local.server.port}")
    private int port;

    @MockitoSpyBean
    private DeadLetterService deadLetters;

    private int status(String method, String path, String hostLine, String contentType) throws IOException {
        StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        if (hostLine != null) {
            req.append("Host: ").append(hostLine).append("\r\n");
        }
        if (contentType != null) {
            req.append("Content-Type: ").append(contentType).append("\r\n");
        }
        req.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(buf);
            String statusLine = buf.toString(StandardCharsets.ISO_8859_1)
                    .lines()
                    .findFirst()
                    .orElse("");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal;x=1/v1/admin/retry-dlq",
                "/%69nternal/v1/admin/retry-dlq",
                "//internal/v1/admin/retry-dlq",
                "/internal%2Fv1/admin/retry-dlq"
            })
    void craftedPathsWithAHostileHostAreRefused(String path) throws Exception {
        assertThat(status("POST", path, "evil.example", "application/json")).isEqualTo(400);
        verify(deadLetters, never()).retry();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal;x=1/v1/admin/retry-dlq",
                "/%69nternal/v1/admin/retry-dlq",
                "//internal/v1/admin/retry-dlq",
                "/internal%2Fv1/admin/retry-dlq",
                RETRY
            })
    void aSimpleCrossSitePostWithAGoodHostNeverReachesTheHandler(String path) throws Exception {
        assertThat(status("POST", path, "localhost:" + port, "text/plain")).isIn(400, 403);
        verify(deadLetters, never()).retry();
    }

    @Test
    void aMissingHostIsRefused() throws Exception {
        assertThat(status("GET", "/internal/v1/tickers", null, null)).isEqualTo(400);
    }

    @Test
    void aNormalRequestStillWorks() throws Exception {
        assertThat(status("GET", "/internal/v1/tickers", "ingest:8083", null)).isEqualTo(200);
        assertThat(status("POST", RETRY, "ingest:8083", "application/json")).isEqualTo(202);
    }

    @Test
    void healthStaysReachableForProbesWithAnyHost() throws Exception {
        assertThat(status("GET", "/actuator/health", "10.0.0.7:8083", null)).isEqualTo(200);
    }
}
