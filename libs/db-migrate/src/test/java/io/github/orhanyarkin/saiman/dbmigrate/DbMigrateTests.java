package io.github.orhanyarkin.saiman.dbmigrate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers.Database;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@ExtendWith(OutputCaptureExtension.class)
class DbMigrateTests {

    private static final String SCHEMA = "ledger";
    private static final String OWNER = SharedContainers.ownerRole(SCHEMA);
    private static final String PASSWORD = SharedContainers.ROLE_PASSWORD;

    @Test
    void migratesAsOwnerAndExitsZero(CapturedOutput output) throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();

        int exit = DbMigrate.run(args(db, OWNER, PASSWORD));

        assertThat(exit).isZero();
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), OWNER, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet history = statement.executeQuery(
                        "SELECT version, success FROM ledger.flyway_schema_history ORDER BY installed_rank")) {
            List<String> versions = new ArrayList<>();
            while (history.next()) {
                assertThat(history.getBoolean("success")).isTrue();
                versions.add(history.getString("version"));
            }
            assertThat(versions).containsExactly("1", "2");
        }
        assertThat(output.getOut())
                .contains("db-migrate: schema=ledger user=ledger_owner from=none to=2 applied=2 durationMs=")
                .doesNotContain(PASSWORD);

        // A second run is a no-op that still exits 0 and reports the current version.
        assertThat(DbMigrate.run(args(db, OWNER, PASSWORD))).isZero();
        assertThat(output.getOut()).contains("from=2 to=2 applied=0");
    }

    @Test
    void contextHasNoDataSourceAndExactlyOneFlyway() {
        Database db = SharedContainers.newPostgresDatabase();

        try (ConfigurableApplicationContext context = DbMigrate.newBuilder().run(args(db, OWNER, PASSWORD))) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(org.flywaydb.core.Flyway.class))
                    .hasSize(1);
        }
    }

    @Test
    void wrongPasswordExitsOneAndNeverLogsIt(CapturedOutput output) {
        Database db = SharedContainers.newPostgresDatabase();
        String wrong = "wrong-pw-marker-7f3a91";

        int exit = DbMigrate.run(args(db, OWNER, wrong));

        assertThat(exit).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(wrong).contains("db-migrate: failed");
    }

    @Test
    void invalidConfigurationFailsInPreflightBeforeAnyConnection(CapturedOutput output) {
        // Unreachable host: only the preflight can fail these quickly, and no connection line may appear.
        Database db = new Database("jdbc:postgresql://127.0.0.1:1/none", "ignored", "ignored");

        assertThat(DbMigrate.run(args(db, "", PASSWORD))).isEqualTo(1);
        assertThat(DbMigrate.run(args(db, OWNER, ""))).isEqualTo(1);
        assertThat(DbMigrate.run(args(db, OWNER, "   "))).isEqualTo(1);
        assertThat(DbMigrate.run(args(db, "_owner", PASSWORD))).isEqualTo(1);
        assertThat(DbMigrate.run(args(db, SharedContainers.appRole(SCHEMA), PASSWORD)))
                .isEqualTo(1);
        assertThat(DbMigrate.run(args(db, SharedContainers.ownerRole("seller_api"), PASSWORD)))
                .isEqualTo(1);
        assertThat(DbMigrate.run(withExtra(args(db, OWNER, PASSWORD), "--spring.flyway.enabled=false")))
                .isEqualTo(1);
        assertThat(DbMigrate.run(new String[] {"--spring.flyway.user=" + OWNER, "--spring.flyway.password=x"}))
                .isEqualTo(1);

        assertThat(output.getAll())
                .contains(
                        "db-migrate: preflight failed: spring.flyway.user must be set",
                        "db-migrate: preflight failed: spring.flyway.password must be set",
                        "db-migrate: preflight failed: spring.flyway.user must be a *_owner role",
                        "db-migrate: preflight failed: spring.flyway.user must be <spring.flyway.default-schema>_owner",
                        "db-migrate: preflight failed: spring.flyway.enabled=false",
                        "db-migrate: preflight failed: spring.flyway.url must be set")
                .doesNotContain("db-migrate: failed")
                .doesNotContain("org.flywaydb")
                .doesNotContain("HikariPool")
                .doesNotContain(PASSWORD);
    }

    @Test
    void nonOwnerUserCreatesNothing() throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(DbMigrate.run(args(db, SharedContainers.appRole(SCHEMA), PASSWORD)))
                .isEqualTo(1);
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
                Statement statement = connection.createStatement();
                ResultSet tables = statement.executeQuery("SELECT to_regclass('ledger.widget')")) {
            tables.next();
            assertThat(tables.getString(1)).isNull();
        }
    }

    @Test
    void requestedReadsPropertyAndCommandLineArgument() {
        assertThat(DbMigrate.requested(new String[] {})).isFalse();
        assertThat(DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "=server"}))
                .isFalse();
        assertThat(DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "=migrate"}))
                .isTrue();

        // SAIMAN_RUN_MODE as a real environment variable, through a fake systemEnvironment source.
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("SAIMAN_RUN_MODE", "migrate")));
        assertThat(DbMigrate.requested(new String[] {}, env)).isTrue();

        // And as a system property.
        String previous = System.getProperty(DbMigrate.RUN_MODE);
        System.setProperty(DbMigrate.RUN_MODE, "migrate");
        try {
            assertThat(DbMigrate.requested(new String[] {})).isTrue();
        } finally {
            if (previous == null) {
                System.clearProperty(DbMigrate.RUN_MODE);
            } else {
                System.setProperty(DbMigrate.RUN_MODE, previous);
            }
        }
        assertThat(DbMigrate.requested(new String[] {})).isFalse();
    }

    /** The service's own settings, as application.yaml plus secrets would supply them. */
    private static String[] args(Database db, String user, String password) {
        return new String[] {
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + user,
            "--spring.flyway.password=" + password,
            "--spring.flyway.schemas=" + SCHEMA,
            "--spring.flyway.default-schema=" + SCHEMA,
            "--spring.flyway.locations=classpath:db/testmigration",
            "--spring.flyway.placeholders.app_role=" + SharedContainers.appRole(SCHEMA)
        };
    }

    private static String[] withExtra(String[] args, String extra) {
        String[] all = java.util.Arrays.copyOf(args, args.length + 1);
        all[args.length] = extra;
        return all;
    }
}
