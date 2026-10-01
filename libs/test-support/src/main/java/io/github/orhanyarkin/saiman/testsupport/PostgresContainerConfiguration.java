package io.github.orhanyarkin.saiman.testsupport;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A fresh database on the JVM's shared Postgres container for every application context that imports this
 * configuration ({@link SharedContainers#newPostgresDatabase()}). Only the container is shared: each cached context
 * still runs its Flyway migrations against, and asserts on, its own empty database, as it did when every context
 * started its own container.
 *
 * <p>Deliberately not {@code @ServiceConnection} on the container: that would also register Flyway connection
 * details pointing at the container's default database, and every context would share it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

    @Bean
    JdbcConnectionDetails postgresConnectionDetails() {
        SharedContainers.Database database = SharedContainers.newPostgresDatabase();
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return database.username();
            }

            @Override
            public String getPassword() {
                return database.password();
            }

            @Override
            public String getJdbcUrl() {
                return database.jdbcUrl();
            }
        };
    }
}
