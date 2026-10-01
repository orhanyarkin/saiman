package io.github.orhanyarkin.saiman.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres and Redpanda for integration tests as Spring beans: their lifecycle follows the (cached) application
 * context, so every test class that shares a context shares one pair of containers, and {@code @ServiceConnection}
 * supplies the datasource and Kafka bootstrap properties. Same images as deploy/compose.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection
    RedpandaContainer redpanda() {
        return new RedpandaContainer(DockerImageName.parse("redpandadata/redpanda:v26.2.3"));
    }
}
