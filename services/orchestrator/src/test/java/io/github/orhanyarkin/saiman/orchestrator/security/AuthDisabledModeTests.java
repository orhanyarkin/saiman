package io.github.orhanyarkin.saiman.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * {@code saiman.auth.enabled=false} with the explicit acknowledgement (ADR-0023): the orchestrator defines no security
 * chain of its own, so Saiman's rules do not apply, and Boot's default chain is what answers. Nothing under {@code
 * /api/**} may be served to an anonymous caller in that mode.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "saiman.auth.enabled=false",
            "saiman.auth.allow-disabled-insecure=true",
            "saiman.auth.reader-token-sha256=",
            "saiman.auth.operator-token-sha256="
        })
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AuthDisabledModeTests {

    @Autowired
    private RestTestClient http;

    @Autowired
    private ApplicationContext context;

    @Test
    void saimansChainIsNotDefinedAndBootsDefaultChainDeniesTheApi() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class).keySet())
                .doesNotContain("orchestratorApiSecurity");
        RestTestClient anonymous = http.mutate()
                .defaultHeaders(headers -> headers.remove("Authorization"))
                .build();

        for (String path :
                List.of("/api/v1/runs", "/api/v1/spend", "/api/v1/approvals", "/api/v1/ping", "/api/v1/me")) {
            anonymous
                    .get()
                    .uri(path)
                    .exchange()
                    .expectStatus()
                    .value(status -> assertThat(status).as(path).isIn(401, 302, 403));
        }
    }
}
