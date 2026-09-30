package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends to and reads the {@code run_event} log.
 *
 * <p>The seq comes from {@code UPDATE run SET next_seq = next_seq + 1 ... RETURNING next_seq}: the
 * update row-locks the run until the transaction ends, so concurrent appenders for one run are
 * serialised and the seqs are gap-free, and they commit in seq order. The event is handed to {@link
 * RunEventBus} only after the transaction committed (a rolled-back event is never streamed).
 *
 * <p>Lock order: this takes only the {@code run} row. Callers must not append while they hold
 * {@code approval} or {@code payment_intent} rows they lock later, the same order as everywhere
 * else (approval -> payment_intent -> run -> spend_day).
 */
@Service
public class RunEventAppender {

    private final JdbcClient jdbc;
    private final RunEventCodec codec;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public RunEventAppender(
            JdbcClient jdbc,
            RunEventCodec codec,
            ApplicationEventPublisher publisher,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.publisher = publisher;
        // Programmatic, so the emitter lambda below (a self-call) is transactional too.
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Appends one event (in the caller's transaction, or in its own).
     *
     * @throws IllegalArgumentException if {@code data} is not the payload class of {@code type}
     * @throws IllegalStateException if the run does not exist
     */
    public RunEvent append(UUID runId, RunEventType type, RunEventData data) {
        String payload = codec.encodePayload(type, data);
        return Objects.requireNonNull(tx.execute(status -> insert(runId, type, data, payload)));
    }

    private RunEvent insert(UUID runId, RunEventType type, RunEventData data, String payload) {
        Integer seq = jdbc.sql("UPDATE run SET next_seq = next_seq + 1 WHERE id = :id RETURNING next_seq")
                .param("id", runId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("unknown run"));
        // Postgres keeps microseconds: truncate so the live event equals its replayed copy.
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO run_event (run_id, seq, type, payload, created_at)
                        VALUES (:runId, :seq, :type, CAST(:payload AS jsonb), :createdAt)
                        """)
                .param("runId", runId)
                .param("seq", seq)
                .param("type", type.name())
                .param("payload", payload)
                .param("createdAt", Timestamp.from(occurredAt))
                .update();
        RunEvent event = new RunEvent(runId, seq, type, occurredAt, data);
        publisher.publishEvent(new RunEventAppended(event));
        return event;
    }

    /** Every event of the run with {@code seq > afterSeq}, in seq order. */
    public List<RunEvent> readAfter(UUID runId, int afterSeq) {
        return jdbc.sql("""
                        SELECT seq, type, payload::text AS payload, created_at FROM run_event
                         WHERE run_id = :runId AND seq > :after ORDER BY seq
                        """)
                .param("runId", runId)
                .param("after", afterSeq)
                .query((rs, row) -> {
                    RunEventType type = RunEventType.valueOf(rs.getString("type"));
                    return new RunEvent(
                            runId,
                            rs.getInt("seq"),
                            type,
                            rs.getTimestamp("created_at").toInstant(),
                            codec.decodePayload(type, rs.getString("payload")));
                })
                .list();
    }

    /** An emitter bound to one run. */
    public RunEventEmitter emitterFor(UUID runId) {
        return (type, data) -> append(runId, type, data);
    }
}
