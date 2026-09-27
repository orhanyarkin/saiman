package io.github.orhanyarkin.saiman.evals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * evals is a CLI app exempt from an HTTP health endpoint (docs/PLAN.md, M0): health is asserted
 * in-context, and the app is started through the real {@code SpringApplication} path to prove it
 * runs without a web server.
 */
@SpringBootTest
class EvalsApplicationTests {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private ApplicationContext context;

    @Test
    void healthIsUpInContext() {
        assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void runnerIsOffInTests() {
        assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
    }

    @Test
    void startsAsNonWebApplication() {
        SpringApplication application = new SpringApplication(EvalsApplication.class);
        try (ConfigurableApplicationContext started = application.run()) {
            assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
            assertThat(started.getEnvironment().getProperty("local.server.port"))
                    .isNull();
        }
    }
}
