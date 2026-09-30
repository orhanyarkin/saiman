package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code tool_result}: sanitised results of paid calls, for the per-run dedupe. */
@Repository
class ToolResultStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ToolResultStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void save(UUID paymentIntentId, ToolInvocation invocation, SanitizedToolResult result) {
        jdbc.sql("""
                        INSERT INTO tool_result (payment_intent_id, run_id, tool, args_hash, result)
                        VALUES (:intentId, :runId, :tool, :argsHash, CAST(:result AS jsonb))
                        ON CONFLICT (payment_intent_id) DO NOTHING
                        """)
                .param("intentId", paymentIntentId)
                .param("runId", invocation.runId())
                .param("tool", invocation.tool())
                .param("argsHash", invocation.argsHash())
                .param("result", json.writeValueAsString(result))
                .update();
    }

    /** The stored result of a paid call, if one was stored. */
    Optional<SanitizedToolResult> find(UUID paymentIntentId) {
        return jdbc.sql("SELECT result::text FROM tool_result WHERE payment_intent_id = :id")
                .param("id", paymentIntentId)
                .query(String.class)
                .optional()
                .map(text -> json.readValue(text, SanitizedToolResult.class));
    }

    /**
     * True if an earlier identical call of this run is HELD (signed, outcome unknown): the same call
     * is then never sent again under a fresh key.
     */
    boolean hasHeld(UUID runId, String tool, String argsHash) {
        return jdbc.sql("SELECT count(*) FROM payment_intent WHERE run_id = :runId AND tool = :tool"
                                + " AND args_hash = :argsHash AND status = 'HELD'")
                        .param("runId", runId)
                        .param("tool", tool)
                        .param("argsHash", argsHash)
                        .query(Integer.class)
                        .single()
                > 0;
    }
}
