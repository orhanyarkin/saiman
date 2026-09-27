package io.github.orhanyarkin.saiman.evals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * evals is a CLI app exempt from an HTTP health endpoint (human decision, M0 plan §T4): it runs
 * with {@code spring.main.web-application-type=none}, so health is asserted in-context via
 * {@link HealthEndpoint} instead of a real HTTP probe.
 */
@SpringBootTest
class EvalsApplicationHealthTest {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private Environment environment;

    @Test
    void healthIsUpInContext() {
        assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void noWebServerStarted() {
        // Boot only publishes this property once an embedded web server actually binds a port.
        assertThat(environment.getProperty("local.server.port")).isNull();
    }
}
