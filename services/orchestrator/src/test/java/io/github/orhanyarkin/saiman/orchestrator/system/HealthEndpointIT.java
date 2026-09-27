package io.github.orhanyarkin.saiman.orchestrator.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Every M0 web service exposes Actuator health, with liveness/readiness probes enabled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointIT extends AbstractIntegrationTest {

    @Test
    void healthIsUp() {
        ResponseEntity<String> response =
                restClient().get().uri("/actuator/health").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessAndReadinessProbesAreExposed() {
        assertThat(restClient()
                        .get()
                        .uri("/actuator/health/liveness")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restClient()
                        .get()
                        .uri("/actuator/health/readiness")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
