package io.github.orhanyarkin.saiman.ingest.dlq;

import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ingest.dead_letter}: parked documents with stage and error class, never document text. */
@Repository
public class DeadLetterRepository {

    private static final int MAX_MESSAGE = 500;

    private final JdbcClient jdbc;

    public DeadLetterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void park(String externalId, String stage, Throwable error, int attempts) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.length() > MAX_MESSAGE) {
            message = message.substring(0, MAX_MESSAGE);
        }
        jdbc.sql("""
                        INSERT INTO dead_letter (source, external_id, stage, error_class, error_message, attempts)
                        VALUES (:s, :e, :stage, :cls, :msg, :attempts)
                        ON CONFLICT (source, external_id, stage) DO UPDATE
                            SET error_class = EXCLUDED.error_class, error_message = EXCLUDED.error_message,
                                attempts = EXCLUDED.attempts, created_at = now()
                        """)
                .param("s", DocumentRepository.SOURCE)
                .param("e", externalId)
                .param("stage", stage)
                .param("cls", error.getClass().getSimpleName())
                .param("msg", message)
                .param("attempts", attempts)
                .update();
    }

    public void clear(String externalId) {
        jdbc.sql("DELETE FROM dead_letter WHERE source = :s AND external_id = :e")
                .param("s", DocumentRepository.SOURCE)
                .param("e", externalId)
                .update();
    }

    public void clearAll() {
        jdbc.sql("DELETE FROM dead_letter").update();
    }

    public long count() {
        Long n = jdbc.sql("SELECT count(*) FROM dead_letter").query(Long.class).single();
        return n;
    }
}
