package io.github.orhanyarkin.saiman.ingest;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres with pgvector as a Spring bean: its lifecycle follows the (cached) application context
 * and {@code @ServiceConnection} supplies the datasource properties.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres"));
    }
}
