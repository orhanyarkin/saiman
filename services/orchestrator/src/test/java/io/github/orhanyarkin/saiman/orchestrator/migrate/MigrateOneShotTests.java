package io.github.orhanyarkin.saiman.orchestrator.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.dbmigrate.DbMigrate;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers.Database;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The orchestrator's one-shot entry point (ADR-0027) against its real migrations and service yaml: the owner
 * credential is the only input, and nothing but Flyway is started.
 */
class MigrateOneShotTests {

    private static final String SCHEMA = "orchestrator";
    private static final String OWNER = SharedContainers.ownerRole(SCHEMA);
    private static final String PASSWORD = SharedContainers.ROLE_PASSWORD;

    @Test
    void migratesTheRealMigrationsAsOwnerOnAFreshDatabase() throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();

        int exit = DbMigrate.run(ownerOnlyArgs(db));

        assertThat(exit).isZero();
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), OWNER, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet history = statement.executeQuery(
                        "SELECT version, success FROM orchestrator.flyway_schema_history WHERE version IS NOT NULL")) {
            List<String> versions = new ArrayList<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                versions.add(history.getString("version"));
            }
            assertThat(versions).contains("1", "10", "11").hasSize(11);
        }
    }

    @Test
    void oneShotContextStartsOnlyFlywayAndNeedsNoProviderOrWalletKey() {
        Database db = SharedContainers.newPostgresDatabase();
        AtomicReference<List<String>> beanTypes = new AtomicReference<>();
        AtomicReference<Boolean> webContext = new AtomicReference<>();

        // The hook sees the context just before Boot returns it (and DbMigrate closes it).
        int exit = SpringApplication.withHook(
                _ -> new SpringApplicationRunListener() {
                    @Override
                    public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
                        webContext.set(context instanceof WebServerApplicationContext);
                        List<String> types = new ArrayList<>();
                        for (String name : context.getBeanDefinitionNames()) {
                            Class<?> type = context.getType(name);
                            types.add(type == null ? name : type.getName());
                        }
                        beanTypes.set(types);
                    }
                },
                () -> DbMigrate.run(ownerOnlyArgs(db)));

        assertThat(exit).isZero();
        assertThat(webContext.get()).isFalse();
        assertThat(beanTypes.get())
                .isNotEmpty()
                .noneMatch(t -> t.toLowerCase().contains("kafka"))
                .noneMatch(t -> t.toLowerCase().contains("redis"))
                .noneMatch(t -> t.contains("modelrouter") || t.contains("springframework.ai"))
                .noneMatch(t -> t.contains("modulith"))
                .noneMatch(t -> t.contains("apisecurity") || t.contains("DispatcherServlet"))
                .noneMatch(t -> t.contains("javax.sql.DataSource") || t.contains("HikariDataSource"))
                .anyMatch(t -> t.equals("org.flywaydb.core.Flyway"));
        // Only the owner credential was given: no provider key, no wallet key, no app password.
        assertThat(System.getenv()).doesNotContainKeys("OPENAI_API_KEY", "X402_CLIENT_PRIVATE_KEY");
    }

    private static String[] ownerOnlyArgs(Database db) {
        // The service's own yaml supplies schemas, default-schema, locations and the app_role placeholder.
        return new String[] {
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + OWNER,
            "--spring.flyway.password=" + PASSWORD
        };
    }
}
