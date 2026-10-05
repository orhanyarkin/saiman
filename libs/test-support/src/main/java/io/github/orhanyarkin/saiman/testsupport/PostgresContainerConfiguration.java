package io.github.orhanyarkin.saiman.testsupport;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * A fresh database on the JVM's shared Postgres container for every application context that imports this
 * configuration ({@link SharedContainers#newPostgresDatabase()}). Only the container is shared: each cached context
 * still runs its Flyway migrations against, and asserts on, its own empty database, as it did when every context
 * started its own container.
 *
 * <p>Deliberately not {@code @ServiceConnection} on the container: that would also register Flyway connection
 * details pointing at the container's default database, and every context would share it.
 *
 * <p>Which role the context connects as is {@code saiman.test.db.runtime-role} (ADR-0024 transition flag):
 * <ul>
 *   <li>{@code superuser} (default for modules without a service schema): the container's user, as before;
 *   <li>{@code owner}: the service's {@code <schema>_owner} for both the pool and Flyway;
 *   <li>{@code app} (default for the four services): the pool runs as {@code <schema>_app} (DML only) and Flyway as {@code <schema>_owner}, as in
 *       production. The schema is the context's {@code spring.flyway.default-schema}.
 * </ul>
 * Tests that must tamper with the database on purpose inject {@link SuperuserDatabase}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

    /** The container's superuser on this context's database, for tests that deliberately bypass the runtime role. */
    public record SuperuserDatabase(String jdbcUrl, String username, String password) {}

    private static final String RUNTIME_ROLE_PROPERTY = "saiman.test.db.runtime-role";

    private SharedContainers.@Nullable Database database;

    private synchronized SharedContainers.Database database() {
        if (database == null) {
            database = SharedContainers.newPostgresDatabase();
        }
        return database;
    }

    @Bean
    SuperuserDatabase superuserDatabase() {
        SharedContainers.Database db = database();
        return new SuperuserDatabase(db.jdbcUrl(), db.username(), db.password());
    }

    @Bean
    JdbcConnectionDetails postgresConnectionDetails(Environment environment) {
        SharedContainers.Database db = database();
        String mode = mode(environment);
        String schema = environment.getProperty("spring.flyway.default-schema", "");
        String user = switch (mode) {
            case "owner" -> SharedContainers.ownerRole(requireSchema(schema, mode));
            case "app" -> SharedContainers.appRole(requireSchema(schema, mode));
            default -> db.username();
        };
        String password = "superuser".equals(mode) ? db.password() : SharedContainers.ROLE_PASSWORD;
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return user;
            }

            @Override
            public String getPassword() {
                return password;
            }

            @Override
            public String getJdbcUrl() {
                return db.jdbcUrl();
            }
        };
    }

    /**
     * Flyway runs as the schema owner whenever the pool does not run as the superuser. Nested and conditional so
     * modules without Flyway on the classpath can still import this configuration.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails")
    static class FlywayRole {

        @Bean
        FlywayConnectionDetails postgresFlywayConnectionDetails(Environment environment, SuperuserDatabase db) {
            String mode = mode(environment);
            boolean superuser = "superuser".equals(mode);
            String user = superuser
                    ? db.username()
                    : SharedContainers.ownerRole(
                            requireSchema(environment.getProperty("spring.flyway.default-schema", ""), mode));
            String password = superuser ? db.password() : SharedContainers.ROLE_PASSWORD;
            return new FlywayConnectionDetails() {
                @Override
                public String getUsername() {
                    return user;
                }

                @Override
                public String getPassword() {
                    return password;
                }

                @Override
                public String getJdbcUrl() {
                    return db.jdbcUrl();
                }
            };
        }
    }

    private static String mode(Environment environment) {
        // Services (a known flyway default schema) run as their app role like in production; other modules keep
        // the container's user.
        String fallback =
                SharedContainers.SERVICE_SCHEMAS.contains(environment.getProperty("spring.flyway.default-schema", ""))
                        ? "app"
                        : "superuser";
        String mode = environment.getProperty(RUNTIME_ROLE_PROPERTY, fallback);
        if (!mode.equals("superuser") && !mode.equals("owner") && !mode.equals("app")) {
            throw new IllegalStateException(RUNTIME_ROLE_PROPERTY + " must be superuser, owner or app, was " + mode);
        }
        return mode;
    }

    private static String requireSchema(String schema, String mode) {
        if (!SharedContainers.SERVICE_SCHEMAS.contains(schema)) {
            throw new IllegalStateException(RUNTIME_ROLE_PROPERTY + "=" + mode
                    + " needs spring.flyway.default-schema to be one of " + SharedContainers.SERVICE_SCHEMAS
                    + ", was '" + schema + "'");
        }
        return schema;
    }
}
