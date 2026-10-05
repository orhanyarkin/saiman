package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Empties tables as the runtime role. Tests run as {@code orchestrator_app} (ADR-0024), which has no TRUNCATE privilege,
 * so rows are deleted child-first instead.
 */
public final class TestTables {

    private static final String[] CHILD_FIRST = {
        "event_publication",
        "payment_event_log",
        "tool_result",
        "approval",
        "payment_intent",
        "run_event",
        "spend_day",
        "run"
    };

    private TestTables() {}

    /** Deletes every row of the run, payment, approval, event and outbox tables. */
    public static void clearAll(JdbcClient jdbc) {
        for (String table : CHILD_FIRST) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    /** Deletes the outbox publications and the payment event log only. */
    public static void clearOutbox(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM event_publication").update();
        jdbc.sql("DELETE FROM payment_event_log").update();
    }
}
