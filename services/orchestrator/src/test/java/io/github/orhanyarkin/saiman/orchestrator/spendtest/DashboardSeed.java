package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Direct row inserts for the dashboard read tests (explicit timestamps, recognisable secrets). */
public final class DashboardSeed {

    public static final String MARKER_KEY = "MARKER-IDEMPOTENCY-KEY-9f3a1c";
    public static final String MARKER_NONCE = "0xMARKERNONCE00000000000000000000000000000000000000000000deadbeef";
    public static final String MARKER_PAYER = "0xMARKERPAYER0000000000000000000000c0ffee";
    public static final String PAY_TO = "0x00000000000000000000000000000000000000aa";

    private final JdbcClient jdbc;

    public DashboardSeed(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID run(Instant createdAt, String status, long budget, long reserved, long committed) {
        UUID id = UUID.randomUUID();
        runWithId(id, createdAt, status, budget, reserved, committed);
        return id;
    }

    public void runWithId(UUID id, Instant createdAt, String status, long budget, long reserved, long committed) {
        jdbc.sql("""
                        INSERT INTO run (id, question, status, budget_atomic, reserved_atomic, committed_atomic,
                                         llm_budget_usd_micros, llm_cost_usd_micros, created_at)
                        VALUES (:id, 'seeded question', :status, :budget, :reserved, :committed, 150000, 1234,
                                :createdAt)
                        """)
                .param("id", id)
                .param("status", status)
                .param("budget", budget)
                .param("reserved", reserved)
                .param("committed", committed)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();
    }

    /** An intent with the marker secrets filled in, as the spend guard would have recorded them. */
    public UUID intent(UUID runId, String tool, String status, Long amount, String reservedDay) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payment_intent (id, run_id, idempotency_key, tool, args_hash, resource, status,
                                                    amount_atomic, pay_to, network, asset, reserved_day, payer,
                                                    auth_nonce, valid_before, tx_hash)
                        VALUES (:id, :runId, :key, :tool, 'hash', 'http://seller/x', :status, :amount, :payTo,
                                'eip155:84532', '0xasset', CAST(:day AS date), :payer, :nonce, 4102444800, :tx)
                        """)
                .param("id", id)
                .param("runId", runId)
                .param("key", MARKER_KEY + "-" + id)
                .param("tool", tool)
                .param("status", status)
                .param("amount", amount)
                .param("payTo", amount == null ? null : PAY_TO)
                .param("day", reservedDay)
                .param("payer", MARKER_PAYER + id.toString().substring(0, 4))
                .param("nonce", MARKER_NONCE + id.toString().substring(0, 4))
                .param("tx", "SETTLED".equals(status) ? "0xtx" + id.toString().substring(0, 8) : null)
                .update();
        return id;
    }

    public UUID approval(UUID runId, UUID intentId, String status, long amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO approval (id, payment_intent_id, run_id, amount_atomic, pay_to, resource, status,
                                              expires_at)
                        VALUES (:id, :intent, :runId, :amount, :payTo, 'http://seller/x', :status,
                                now() + interval '5 minutes')
                        """)
                .param("id", id)
                .param("intent", intentId)
                .param("runId", runId)
                .param("amount", amount)
                .param("payTo", PAY_TO)
                .param("status", status)
                .update();
        return id;
    }

    public void spendDay(String day, long reserved, long committed) {
        jdbc.sql("INSERT INTO spend_day (day, reserved_atomic, committed_atomic)"
                        + " VALUES (CAST(:day AS date), :reserved, :committed)")
                .param("day", day)
                .param("reserved", reserved)
                .param("committed", committed)
                .update();
    }
}
