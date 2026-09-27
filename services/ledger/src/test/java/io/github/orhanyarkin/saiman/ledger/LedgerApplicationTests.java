package io.github.orhanyarkin.saiman.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Boots the full application context on a random port and hits the real HTTP endpoints, rather
 * than mocking the servlet container: this is the M0 proof that {@code /actuator/health} and the
 * liveness/readiness probe groups are wired end to end. Uses {@link RestClient} (this project's
 * standard HTTP client) instead of the legacy {@code TestRestTemplate}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LedgerApplicationTests {

    @LocalServerPort
    private int port;

    private RestClient restClient() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void healthEndpointReportsUp() {
        String body = restClient().get().uri("/actuator/health").retrieve().body(String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessProbeResponds() {
        assertThat(restClient()
                        .get()
                        .uri("/actuator/health/liveness")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void readinessProbeResponds() {
        assertThat(restClient()
                        .get()
                        .uri("/actuator/health/readiness")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
