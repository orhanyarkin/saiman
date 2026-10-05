package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The run lifecycle columns of {@code run}. The money counters ({@code reserved_atomic}, {@code
 * committed_atomic}) belong to the spend guard and are only read here; the budget is written once,
 * at insert (a trigger makes it immutable).
 */
@Repository
class RunRepository {

    private static final String UNFINISHED = "'QUEUED', 'RUNNING', 'AWAITING_APPROVAL'";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    RunRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insertQueued(UUID id, String question, long budgetAtomic, long llmBudgetUsdMicros) {
        jdbc.sql("""
                        INSERT INTO run (id, question, status, budget_atomic, llm_budget_usd_micros)
                        VALUES (:id, :question, 'QUEUED', :budget, :llmBudget)
                        """)
                .param("id", id)
                .param("question", question)
                .param("budget", budgetAtomic)
                .param("llmBudget", llmBudgetUsdMicros)
                .update();
    }

    void markRunning(UUID id, @Nullable String traceId) {
        jdbc.sql("""
                        UPDATE run SET status = 'RUNNING', started_at = now(), trace_id = :traceId
                         WHERE id = :id AND status = 'QUEUED'
                        """).param("id", id).param("traceId", traceId, Types.VARCHAR).update();
    }

    /** RUNNING <-> AWAITING_APPROVAL; a no-op in any other state. */
    void changeActiveStatus(UUID id, RunStatus from, RunStatus to) {
        jdbc.sql("UPDATE run SET status = :to WHERE id = :id AND status = :from")
                .param("id", id)
                .param("from", from.name())
                .param("to", to.name())
                .update();
    }

    void addLlmCost(UUID id, long usdMicros) {
        int updated = jdbc.sql("UPDATE run SET llm_cost_usd_micros = llm_cost_usd_micros + :cost WHERE id = :id")
                .param("id", id)
                .param("cost", usdMicros)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("unknown run");
        }
    }

    /**
     * Moves an unfinished run to its terminal status.
     *
     * @return the persisted cost at that moment, or empty if the run was already finished
     */
    Optional<RunCost> finish(
            UUID id, RunStatus status, RunEventData.@Nullable Report report, @Nullable FailureCode failure) {
        return jdbc.sql("UPDATE run SET status = :status, result = CAST(:result AS jsonb), failure_code = :failure,"
                        + " finished_at = now() WHERE id = :id AND status IN (" + UNFINISHED + ")"
                        + " RETURNING committed_atomic, llm_cost_usd_micros")
                .param("id", id)
                .param("status", status.name())
                .param("result", report == null ? null : json.writeValueAsString(report), Types.VARCHAR)
                .param("failure", failure == null ? null : failure.name(), Types.VARCHAR)
                .query((rs, row) -> cost(rs))
                .optional();
    }

    Optional<RunSummary> summary(UUID id) {
        return jdbc.sql("""
                        SELECT id, question, status, budget_atomic, reserved_atomic, committed_atomic,
                               llm_cost_usd_micros, failure_code, trace_id, result::text AS result,
                               created_at, started_at, finished_at
                          FROM run WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> {
                    String result = rs.getString("result");
                    return new RunSummary(
                            rs.getObject("id", UUID.class),
                            RunStatus.valueOf(rs.getString("status")),
                            rs.getString("question"),
                            Money.usdc(rs.getLong("budget_atomic")),
                            Money.usdc(rs.getLong("reserved_atomic")),
                            Money.usdc(rs.getLong("committed_atomic")),
                            cost(rs),
                            rs.getString("failure_code"),
                            rs.getString("trace_id"),
                            rs.getTimestamp("created_at").toInstant(),
                            instant(rs.getTimestamp("started_at")),
                            instant(rs.getTimestamp("finished_at")),
                            result == null ? null : json.readValue(result, RunEventData.Report.class));
                })
                .optional();
    }

    /**
     * Newest-first keyset page: runs strictly older than {@code after} in {@code (created_at, id)}
     * order. One row more than {@code limit} is read so the caller can tell whether another page exists.
     */
    List<RunListItem> page(@Nullable RunCursor after, int limit) {
        String where = after == null ? "" : " WHERE (r.created_at, r.id) < (:createdAt, :id)";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                SELECT r.id, r.question, r.status, r.budget_atomic, r.reserved_atomic, r.committed_atomic,
                       r.llm_cost_usd_micros, r.created_at, r.finished_at,
                       (SELECT count(*) FROM approval a WHERE a.run_id = r.id AND a.status = 'PENDING') AS pending
                  FROM run r""" + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit")
                .param("limit", limit + 1);
        if (after != null) {
            spec = spec.param("createdAt", Timestamp.from(after.createdAt())).param("id", after.id());
        }
        return spec.query((rs, row) -> new RunListItem(
                        rs.getObject("id", UUID.class),
                        RunStatus.valueOf(rs.getString("status")),
                        rs.getString("question"),
                        Money.usdc(rs.getLong("budget_atomic")),
                        Money.usdc(rs.getLong("committed_atomic")),
                        Money.usdc(rs.getLong("reserved_atomic")),
                        cost(rs),
                        rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("finished_at")),
                        rs.getInt("pending")))
                .list();
    }

    boolean exists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM run WHERE id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private static RunCost cost(ResultSet rs) throws SQLException {
        return RunCost.of(
                Money.usdc(rs.getLong("committed_atomic")), Money.usdMicros(rs.getLong("llm_cost_usd_micros")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
