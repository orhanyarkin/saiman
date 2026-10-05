package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;
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
