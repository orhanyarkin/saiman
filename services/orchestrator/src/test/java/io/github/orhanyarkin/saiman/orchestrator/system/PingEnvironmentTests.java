package io.github.orhanyarkin.saiman.orchestrator.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/** A configured {@code saiman.environment} (what {@code SAIMAN_ENVIRONMENT} binds to) reaches the ping response. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "saiman.environment=aws-demo")
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PingEnvironmentTests {

    @Autowired
    private RestTestClient client;

    @Test
    void aConfiguredEnvironmentIsReturnedVerbatim() {
        PingResponse body = client.get()
                .uri("/api/v1/ping")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PingResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.environment()).isEqualTo("aws-demo");
    }
}
