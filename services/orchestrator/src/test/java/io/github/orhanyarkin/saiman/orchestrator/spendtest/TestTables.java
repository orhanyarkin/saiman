package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Empties tables as the database superuser. Tests run as {@code orchestrator_app} (ADR-0024), which has neither
 * TRUNCATE nor DELETE on the counter tables, so cleanup goes around the runtime role on purpose.
 */
public final class TestTables {

    private TestTables() {}

    /** Truncates the run, payment, approval, event and outbox tables. */
    public static void clearAll(SuperuserDatabase db) {
        run(
                db,
                "TRUNCATE orchestrator.event_publication, orchestrator.payment_event_log, orchestrator.tool_result,"
                        + " orchestrator.approval, orchestrator.payment_intent, orchestrator.run_event,"
                        + " orchestrator.spend_day, orchestrator.run");
    }

    /** Truncates the outbox publications and the payment event log only. */
    public static void clearOutbox(SuperuserDatabase db) {
        run(db, "TRUNCATE orchestrator.event_publication, orchestrator.payment_event_log");
    }

    private static void run(SuperuserDatabase db, String sql) {
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("test cleanup failed", e);
        }
    }
}
