package io.github.orhanyarkin.saiman.dbmigrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.orhanyarkin.saiman.testsupport.SharedContainers;
import io.github.orhanyarkin.saiman.testsupport.SharedContainers.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The exit code must never be 0 unless Flyway's migrate ran, as exactly the configured, unprivileged owner. */
@ExtendWith(OutputCaptureExtension.class)
class DbMigrateHardeningTests {

    private static final String SCHEMA = "ledger";
    private static final String OWNER = SharedContainers.ownerRole(SCHEMA);
    private static final String PASSWORD = SharedContainers.ROLE_PASSWORD;

    @Test
    void lazyInitializationExitsOneAndMigratesNothing() throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(DbMigrate.run(with(base(db), "--spring.main.lazy-initialization=true")))
                .isEqualTo(1);
        assertNothingMigrated(db, SCHEMA);
    }

    @Test
    void excludingFlywayAutoConfigurationExitsOneAndMigratesNothing() throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(
                        DbMigrate.run(
                                with(
                                        base(db),
                                        "--spring.autoconfigure.exclude=org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")))
                .isEqualTo(1);
        assertNothingMigrated(db, SCHEMA);
    }

    @Test
    void credentialsInTheJdbcUrlAreRejectedWithoutDdl(CapturedOutput output) throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();
        String url = db.jdbcUrl() + "&user=" + db.username() + "&password=" + db.password();

        assertThat(DbMigrate.run(with(base(db), "--spring.flyway.url=" + url))).isEqualTo(1);

        assertNothingMigrated(db, SCHEMA);
        assertThat(output.getAll())
                .contains("db-migrate: preflight failed: spring.flyway.url must not carry")
                .doesNotContain("password=");
    }

    @Test
    void superuserNamedLikeAnOwnerIsRejected(CapturedOutput output) throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();
        createRole(db, "sx_owner", "SUPERUSER");

        assertThat(DbMigrate.run(owned(db, "sx", "sx_owner"))).isEqualTo(1);

        assertNothingMigrated(db, "sx");
        assertThat(output.getAll()).contains("must not be SUPERUSER or BYPASSRLS");
    }

    @Test
    void bypassRlsRoleNamedLikeAnOwnerIsRejected(CapturedOutput output) throws SQLException {
        Database db = SharedContainers.newPostgresDatabase();
        createRole(db, "by_owner", "NOSUPERUSER BYPASSRLS");
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
                Statement statement = connection.createStatement()) {
            statement.execute("GRANT CONNECT ON DATABASE " + databaseName(db) + " TO by_owner");
        }

        assertThat(DbMigrate.run(owned(db, "by", "by_owner"))).isEqualTo(1);

        assertNothingMigrated(db, "by");
        assertThat(output.getAll()).contains("must not be SUPERUSER or BYPASSRLS");
    }

    @Test
    void logsTheRoleTheDatabaseReports(CapturedOutput output) {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(DbMigrate.run(base(db))).isZero();

        assertThat(output.getOut()).contains("db-migrate: schema=ledger user=ledger_owner from=none to=2");
    }

    @Test
    void schemasMustMatchTheOwnersService() {
        Database db = SharedContainers.newPostgresDatabase();

        assertThat(DbMigrate.run(with(base(db), "--spring.flyway.schemas=seller_api")))
                .isEqualTo(1);
    }

    @Test
    void defaultSchemaAndSchemasBothBlankFails(CapturedOutput output) {
        Database db = SharedContainers.newPostgresDatabase();
        String[] args = {
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + OWNER,
            "--spring.flyway.password=" + PASSWORD,
            "--spring.flyway.locations=classpath:db/testmigration"
        };

        assertThat(DbMigrate.run(args)).isEqualTo(1);
        assertThat(output.getAll()).contains("spring.flyway.default-schema or spring.flyway.schemas must be set");
    }

    @Test
    void preflightSeesSecretsFromAConfigTree(@TempDir Path secrets) throws IOException {
        Database db = SharedContainers.newPostgresDatabase();
        Files.writeString(secrets.resolve("spring.flyway.user"), OWNER);
        Files.writeString(secrets.resolve("spring.flyway.password"), PASSWORD);
        List<String> args = new ArrayList<>(Arrays.asList(base(db)));
        args.removeIf(a -> a.startsWith("--spring.flyway.user=") || a.startsWith("--spring.flyway.password="));
        args.add("--spring.config.import=configtree:" + secrets + "/");

        assertThat(DbMigrate.run(args.toArray(String[]::new))).isZero();

        // And a wrong role in the config tree is rejected by the same preflight.
        Files.writeString(secrets.resolve("spring.flyway.user"), "seller_api_owner");
        assertThat(DbMigrate.run(args.toArray(String[]::new))).isEqualTo(1);
    }

    @Test
    void requestedTrimsAndRejectsUnknownModes() {
        assertThat(DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "= migrate "}))
                .isTrue();
        assertThat(DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "=MIGRATE"}))
                .isTrue();
        assertThat(DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "= server "}))
                .isFalse();
        assertThat(DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "="}))
                .isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "=migrat"}));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DbMigrate.requested(new String[] {"--" + DbMigrate.RUN_MODE + "=foo"}));
    }

    private static String[] base(Database db) {
        return owned(db, SCHEMA, OWNER);
    }

    private static String[] owned(Database db, String schema, String user) {
        return new String[] {
            "--spring.datasource.url=" + db.jdbcUrl(),
            "--spring.flyway.user=" + user,
            "--spring.flyway.password=" + PASSWORD,
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.flyway.locations=classpath:db/testmigration",
            "--spring.flyway.placeholders.app_role=" + SharedContainers.appRole(SCHEMA)
        };
    }

    private static String[] with(String[] args, String extra) {
        String[] all = Arrays.copyOf(args, args.length + 1);
        all[args.length] = extra;
        return all;
    }

    private static void createRole(Database db, String role, String attributes) throws SQLException {
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ROLE IF EXISTS " + role);
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + PASSWORD + "' " + attributes);
        }
    }

    private static String databaseName(Database db) {
        String path = db.jdbcUrl().substring(db.jdbcUrl().indexOf("//") + 2);
        return path.substring(path.indexOf('/') + 1, path.indexOf('?'));
    }

    /** No history table and no test table: nothing ran. */
    private static void assertNothingMigrated(Database db, String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT to_regclass('" + schema
                        + ".flyway_schema_history'), to_regclass('" + schema + ".widget')")) {
            rows.next();
            assertThat(rows.getString(1)).isNull();
            assertThat(rows.getString(2)).isNull();
        }
    }
}
