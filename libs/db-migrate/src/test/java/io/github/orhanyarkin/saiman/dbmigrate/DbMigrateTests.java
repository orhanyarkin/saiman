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
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

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
    void contextHasOnlyFlyway() {
        Database db = SharedContainers.newPostgresDatabase();

        try (ConfigurableApplicationContext context = DbMigrate.newBuilder().run(args(db, OWNER, PASSWORD))) {
            assertThat(context.getClass().getName()).doesNotContain("Web");
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanDefinitionNames())
                    .noneMatch(name -> Pattern.compile("kafka|redis|tomcat|modulith", Pattern.CASE_INSENSITIVE)
                            .matcher(name)
                            .find());
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
    void blankUserOrPasswordFailsFast(CapturedOutput output) {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(DbMigrate.run(args(db, "", PASSWORD))).isEqualTo(1);
        assertThat(DbMigrate.run(args(db, OWNER, ""))).isEqualTo(1);
        assertThat(DbMigrate.run(args(db, OWNER, "   "))).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }

    @Test
    void missingUrlFailsFast() {
        assertThat(DbMigrate.run(new String[] {"--spring.flyway.user=" + OWNER, "--spring.flyway.password=x"}))
                .isEqualTo(1);
    }

    @Test
    void disabledFlywayFailsFast() {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(DbMigrate.run(withExtra(args(db, OWNER, PASSWORD), "--spring.flyway.enabled=false")))
                .isEqualTo(1);
    }

    @Test
    void nonOwnerUserFailsFastWithoutConnecting() throws SQLException {
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

        // SAIMAN_RUN_MODE is resolved through the same property name by StandardEnvironment.
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
