package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-0024: these tests run as {@code orchestrator_app}, the role the service connects as. It reads and writes rows,
 * and nothing else: no way around the immutability and monotonic triggers, no DDL, no TRUNCATE, no Flyway history.
 */
class RuntimeRoleGrantsTests extends SpendTestSupport {

    private static final String PERMISSION_DENIED = "42501";
    private static final String CHECK_VIOLATION = "23514";

    @Autowired
    private SuperuserDatabase superuser;

    @Test
    void theServiceConnectsAsTheAppRoleWhichIsNotASuperuser() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("orchestrator_app");
        assertThat(jdbc.sql("SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls FROM pg_roles"
                                + " WHERE rolname = current_user")
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void theAppRoleCannotTurnTheTriggersOffOrReplaceThem() {
        assertThat(sqlState(
                        () -> jdbc.sql("SET session_replication_role = replica").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(
                        () -> jdbc.sql("ALTER TABLE run DISABLE TRIGGER ALL").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("ALTER TABLE run DISABLE TRIGGER run_committed_monotonic")
                        .update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("ALTER TABLE spend_day DISABLE TRIGGER spend_day_committed_monotonic")
                        .update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() ->
                        jdbc.sql("DROP TRIGGER run_committed_monotonic ON run").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("""
                                CREATE OR REPLACE FUNCTION committed_atomic_is_monotonic() RETURNS trigger
                                LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$
                                """).update())).isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("DROP FUNCTION committed_atomic_is_monotonic() CASCADE")
                        .update()))
                .isEqualTo(PERMISSION_DENIED);
    }

    @Test
    void committedAtomicCanGrowButNeverShrinkOnARunOrADay() {
        UUID run = createRun(50_000);
        jdbc.sql("INSERT INTO spend_day (day, reserved_atomic, committed_atomic) VALUES (current_date, 0, 7000)")
                .update();

        jdbc.sql("UPDATE run SET committed_atomic = 10000 WHERE id = :id")
                .param("id", run)
                .update();
        jdbc.sql("UPDATE run SET committed_atomic = 10000 WHERE id = :id") // unchanged is fine
                .param("id", run)
                .update();
        jdbc.sql("UPDATE spend_day SET committed_atomic = committed_atomic + 1000")
                .update();

        assertThat(sqlState(() -> jdbc.sql("UPDATE run SET committed_atomic = committed_atomic - 1 WHERE id = :id")
                        .param("id", run)
                        .update()))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(sqlState(() -> jdbc.sql("UPDATE run SET committed_atomic = 0 WHERE id = :id")
                        .param("id", run)
                        .update()))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(sqlState(() ->
                        jdbc.sql("UPDATE spend_day SET committed_atomic = 0").update()))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(run(run).committed()).isEqualTo(10_000);
        assertThat(today().committed()).isEqualTo(8_000);
    }

    @Test
    void counterRowsCannotBeDeletedButAcknowledgedPublicationsCanBe() {
        UUID run = createRun(50_000);
        jdbc.sql("INSERT INTO spend_day (day) VALUES (current_date)").update();

        assertThat(sqlState(() -> jdbc.sql("DELETE FROM spend_day").update())).isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("DELETE FROM run").update())).isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("DELETE FROM payment_intent").update()))
                .isEqualTo(PERMISSION_DENIED);
        jdbc.sql("DELETE FROM event_publication").update(); // Modulith's completion-mode delete

        assertThat(run(run).budget()).isEqualTo(50_000);
        assertThat(jdbc.sql("SELECT count(*) FROM spend_day")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void appendOnlyTablesAcceptInsertsOnly() {
        for (String table : List.of("run_event", "tool_result", "payment_event_log")) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(hasTablePrivilege(table, privilege))
                        .as(table + " " + privilege)
                        .isFalse();
            }
            assertThat(hasTablePrivilege(table, "INSERT")).as(table + " INSERT").isTrue();
        }
        assertThat(sqlState(
                        () -> jdbc.sql("UPDATE run_event SET payload = payload").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("DELETE FROM tool_result").update())).isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("DELETE FROM payment_event_log").update()))
                .isEqualTo(PERMISSION_DENIED);
    }

    @Test
    void whatARowSaidWhenItWasInsertedCannotBeChangedButItsStateCan() {
        UUID run = createRun(50_000);
        assertThat(sqlState(() -> jdbc.sql("UPDATE run SET question = 'x' WHERE id = :id")
                        .param("id", run)
                        .update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("UPDATE run SET llm_budget_usd_micros = 1 WHERE id = :id")
                        .param("id", run)
                        .update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() ->
                        jdbc.sql("UPDATE payment_intent SET resource = 'x'").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("UPDATE payment_intent SET idempotency_key = 'x'")
                        .update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(
                        () -> jdbc.sql("UPDATE approval SET amount_atomic = 1").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("UPDATE approval SET pay_to = 'x'").update()))
                .isEqualTo(PERMISSION_DENIED);
        for (String column : List.of("status", "decided_at", "decided_by")) {
            assertThat(hasColumnPrivilege("approval", column))
                    .as("approval." + column)
                    .isTrue();
        }
        assertThat(hasColumnPrivilege("approval", "amount_atomic")).isFalse();
        assertThat(hasColumnPrivilege("run", "status")).isTrue();
        assertThat(hasColumnPrivilege("run", "budget_atomic")).isFalse();
        assertThat(hasColumnPrivilege("payment_intent", "status")).isTrue();
        assertThat(hasColumnPrivilege("spend_day", "committed_atomic")).isTrue();
        jdbc.sql("UPDATE run SET status = 'FAILED' WHERE id = :id")
                .param("id", run)
                .update();
    }

    /** The code deletes nothing itself: the only DELETE the app role may hold is Modulith's on event_publication. */
    @Test
    void theAppRoleHoldsDeleteOnlyWhereTheCodeNeedsIt() throws IOException {
        Set<String> deletedByCode = new TreeSet<>(Set.of("event_publication")); // Spring Modulith's registry
        Pattern delete = Pattern.compile("DELETE\\s+FROM\\s+([a-z_]+)");
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source :
                    sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = delete.matcher(Files.readString(source));
                while (matcher.find()) {
                    deletedByCode.add(matcher.group(1));
                }
            }
        }

        List<String> granted = jdbc.sql("SELECT table_name FROM information_schema.role_table_grants"
                        + " WHERE grantee = current_user AND privilege_type = 'DELETE' AND table_schema = 'orchestrator'"
                        + " ORDER BY table_name")
                .query(String.class)
                .list();

        assertThat(granted).containsExactlyElementsOf(deletedByCode);
    }

    private boolean hasTablePrivilege(String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT has_table_privilege(current_user, :table, :privilege)")
                .param("table", "orchestrator." + table)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single());
    }

    /** Column-level UPDATE; has_column_privilege is also true when the whole table is granted. */
    private boolean hasColumnPrivilege(String table, String column) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT has_column_privilege(current_user, :table, :column, 'UPDATE')")
                .param("table", "orchestrator." + table)
                .param("column", column)
                .query(Boolean.class)
                .single());
    }

    @Test
    void theAppRoleCannotRunDdlTruncateOrReadFlywayHistory() {
        assertThat(sqlState(() -> jdbc.sql("CREATE TABLE sneaky (id int)").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(
                        () -> jdbc.sql("ALTER TABLE run ADD COLUMN sneaky int").update()))
                .isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() -> jdbc.sql("TRUNCATE run CASCADE").update())).isEqualTo(PERMISSION_DENIED);
        assertThat(sqlState(() ->
                        jdbc.sql("SELECT * FROM flyway_schema_history").query().listOfRows()))
                .isEqualTo(PERMISSION_DENIED);
    }

    @Test
    void aSuperuserStillCanBypassTheTriggersWhichIsWhyOnlyTheAppRoleIsUsed() throws SQLException {
        UUID run = createRun(50_000);
        jdbc.sql("UPDATE run SET committed_atomic = 10000 WHERE id = :id")
                .param("id", run)
                .update();

        try (Connection connection =
                        DriverManager.getConnection(superuser.jdbcUrl(), superuser.username(), superuser.password());
                Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.executeUpdate("UPDATE orchestrator.run SET committed_atomic = 0 WHERE id = '" + run + "'");
        }

        assertThat(run(run).committed()).isZero();
    }

    private static String sqlState(Supplier<?> statement) {
        Throwable failure = catchThrowable(statement::get);
        assertThat(failure).as("the statement must fail").isNotNull();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        throw new AssertionError("no SQLException in the cause chain", failure);
    }
}
