package io.github.orhanyarkin.saiman.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Verifies the actuator health group and liveness/readiness probes respond on a real HTTP port,
 * proving M0's "every web service has an Actuator health endpoint" acceptance criterion.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than a Spring test-client module: it needs no extra
 * test dependency beyond {@code spring-boot-starter-test}, which already brings {@link
 * LocalServerPort}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IngestApplicationHealthTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void healthIsUp() throws IOException, InterruptedException {
        assertHealthUp("/actuator/health");
    }

    @Test
    void livenessRespondsWithOk() throws IOException, InterruptedException {
        assertHealthUp("/actuator/health/liveness");
    }

    @Test
    void readinessRespondsWithOk() throws IOException, InterruptedException {
        assertHealthUp("/actuator/health/readiness");
    }

    private void assertHealthUp(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
