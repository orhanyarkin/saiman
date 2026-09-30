package io.github.orhanyarkin.saiman.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Boots the application on a random port and calls the real Actuator endpoints over HTTP: health
 * and the liveness/readiness probes are up, health hides component details, info carries build
 * metadata only, and every other Actuator endpoint does not exist at all.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@org.springframework.context.annotation.Import({TestcontainersConfiguration.class, TestModelRouterConfiguration.class})
class ActuatorEndpointsTests {

    @Autowired
    private RestTestClient client;

    @Value("${spring.application.name}")
    private String serviceName;

    @Test
    void healthIsUpWithoutComponentDetails() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
                .jsonPath("$.components")
                .doesNotExist()
                .jsonPath("$.details")
                .doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void probesAreUp(String uri) {
        client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void infoShowsBuildMetadataOnly() {
        client.get()
                .uri("/actuator/info")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .value(info -> assertThat(info).containsOnlyKeys("build"));
        client.get()
                .uri("/actuator/info")
                .exchange()
                .expectBody()
                .jsonPath("$.build.name")
                .isEqualTo(serviceName);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/env",
                "/actuator/configprops",
                "/actuator/beans",
                "/actuator/heapdump",
                "/actuator/loggers",
                "/actuator/metrics"
            })
    void otherActuatorEndpointsDoNotExist(String uri) {
        client.get().uri(uri).exchange().expectStatus().isNotFound();
    }
}
