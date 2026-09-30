package io.github.orhanyarkin.saiman.ingest.store;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ingest.source_cursor}: where the windowed scan of a ticker stopped. */
@Repository
public class CursorRepository {

    private final JdbcClient jdbc;

    public CursorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Cursor(long index, boolean done) {}

    public Optional<Cursor> find(String ticker) {
        return jdbc.sql("SELECT cursor_index, done FROM source_cursor WHERE source = :s AND ticker = :t")
                .param("s", DocumentRepository.SOURCE)
                .param("t", ticker)
                .query((rs, n) -> new Cursor(rs.getLong("cursor_index"), rs.getBoolean("done")))
                .optional();
    }

    public void save(String ticker, long index, boolean done) {
        jdbc.sql("""
                        INSERT INTO source_cursor (source, ticker, cursor_index, done) VALUES (:s, :t, :i, :d)
                        ON CONFLICT (source, ticker) DO UPDATE
                            SET cursor_index = EXCLUDED.cursor_index, done = EXCLUDED.done, updated_at = now()
                        """)
                .param("s", DocumentRepository.SOURCE)
                .param("t", ticker)
                .param("i", index)
                .param("d", done)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM source_cursor").update();
    }
}
