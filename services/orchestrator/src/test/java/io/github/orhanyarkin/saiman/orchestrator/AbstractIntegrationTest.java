package io.github.orhanyarkin.saiman.orchestrator;

import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for orchestrator integration tests: one shared Postgres container for the whole
 * class hierarchy, wired via Spring Boot's {@code @ServiceConnection} so no
 * {@code spring.datasource.*} property overrides are needed in tests, plus a plain {@code
 * RestClient} bound to the random test port (no {@code spring-boot-starter-restclient} needed for
 * {@code RestTemplateBuilder}).
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres"));

    @LocalServerPort
    private int port;

    protected RestClient restClient() {
        return RestClient.create("http://localhost:" + port);
    }
}
