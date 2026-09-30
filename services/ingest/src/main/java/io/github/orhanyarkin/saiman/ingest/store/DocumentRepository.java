package io.github.orhanyarkin.saiman.ingest.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code ingest.source_document}: one row per disclosure, the unit of idempotency. */
@Repository
public class DocumentRepository {

    public static final String SOURCE = "kap";

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The parts of a stored document the pipeline decides on. */
    public record Stored(
            String id, DocumentStatus status, @Nullable String contentHash, int attempts) {}

    /** What the pipeline knows about a disclosure once its detail is fetched. */
    public record NewDocument(
            long disclosureIndex,
            String ticker,
            String title,
            String disclosureClass,
            String disclosureType,
            String reason,
            @Nullable String relatedIndex,
            Instant publishedAt,
            @Nullable String contentHash) {

        public String id() {
            return documentId(disclosureIndex);
        }

        public String sourceUrl() {
            return "https://www.kap.org.tr/tr/Bildirim/" + disclosureIndex;
        }
    }

    public static String documentId(long disclosureIndex) {
        return SOURCE + ":" + disclosureIndex;
    }

    public Optional<Stored> find(String id) {
        return jdbc.sql("SELECT id, status, content_hash, attempts FROM source_document WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Stored(
                        rs.getString("id"),
                        DocumentStatus.valueOf(rs.getString("status")),
                        rs.getString("content_hash"),
                        rs.getInt("attempts")))
                .optional();
    }

    /** Inserts or refreshes the row as {@code PENDING}; chunks may reference it from now on. */
    public void upsertPending(NewDocument doc) {
        upsert(doc, DocumentStatus.PENDING);
    }

    /** Records a disclosure that is never chunked (a cancellation notice) so re-scans skip it. */
    public void upsertTerminal(NewDocument doc, DocumentStatus status) {
        upsert(doc, status);
    }

    private void upsert(NewDocument doc, DocumentStatus status) {
        jdbc.sql("""
                        INSERT INTO source_document (id, source, external_id, ticker, title, disclosure_class,
                            disclosure_type, reason, related_index, source_url, published_at, retrieved_at,
                            content_hash, status)
                        VALUES (:id, :source, :externalId, :ticker, :title, :cls, :type, :reason, :related, :url,
                            :published, now(), :hash, :status)
                        ON CONFLICT (source, external_id) DO UPDATE SET
                            ticker = EXCLUDED.ticker, title = EXCLUDED.title,
                            disclosure_class = EXCLUDED.disclosure_class, disclosure_type = EXCLUDED.disclosure_type,
                            reason = EXCLUDED.reason, related_index = EXCLUDED.related_index,
                            published_at = EXCLUDED.published_at, retrieved_at = now(),
                            content_hash = EXCLUDED.content_hash, status = EXCLUDED.status, updated_at = now()
                        """)
                .param("id", doc.id())
                .param("source", SOURCE)
                .param("externalId", Long.toString(doc.disclosureIndex()))
                .param("ticker", doc.ticker())
                .param("title", doc.title())
                .param("cls", doc.disclosureClass())
                .param("type", doc.disclosureType())
                .param("reason", doc.reason())
                .param("related", doc.relatedIndex())
                .param("url", doc.sourceUrl())
                .param("published", java.sql.Timestamp.from(doc.publishedAt()))
                .param("hash", doc.contentHash())
                .param("status", status.name())
                .update();
    }

    public int countChunks(String documentId) {
        Integer n = jdbc.sql("SELECT count(*) FROM chunk WHERE document_id = :id")
                .param("id", documentId)
                .query(Integer.class)
                .single();
        return n;
    }

    /** Marks the document indexed and drops chunks left over from an earlier, longer version. */
    public void finalizeIndexed(String documentId, List<String> keepChunkIds) {
        if (keepChunkIds.isEmpty()) {
            jdbc.sql("DELETE FROM chunk WHERE document_id = :id")
                    .param("id", documentId)
                    .update();
        } else {
            jdbc.sql("DELETE FROM chunk WHERE document_id = :id AND id NOT IN (:keep)")
                    .param("id", documentId)
                    .param("keep", keepChunkIds)
                    .update();
        }
        jdbc.sql("UPDATE source_document SET status = 'INDEXED', chunk_count = :n, attempts = 0, updated_at = now()"
                        + " WHERE id = :id")
                .param("n", keepChunkIds.size())
                .param("id", documentId)
                .update();
    }

    /** A correction or cancellation replaces this disclosure; it drops out of retrieval. */
    public void markSuperseded(String documentId) {
        jdbc.sql("UPDATE source_document SET status = 'SUPERSEDED', updated_at = now() WHERE id = :id"
                        + " AND status <> 'BLOCKED'")
                .param("id", documentId)
                .update();
    }

    /** KAP blocked the disclosure (personal-data removal): delete its chunks and never index it again. */
    public void markBlocked(String documentId) {
        jdbc.sql("DELETE FROM chunk WHERE document_id = :id")
                .param("id", documentId)
                .update();
        jdbc.sql("UPDATE source_document SET status = 'BLOCKED', content_hash = NULL, chunk_count = 0,"
                        + " updated_at = now() WHERE id = :id")
                .param("id", documentId)
                .update();
    }

    /** Counts a failed attempt; parks the document as {@code FAILED} once {@code maxAttempts} is reached. */
    public int recordFailure(String documentId, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE source_document
                        SET attempts = attempts + 1,
                            status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE status END,
                            updated_at = now()
                        WHERE id = :id
                        RETURNING attempts
                        """)
                .param("max", maxAttempts)
                .param("id", documentId)
                .query(Integer.class)
                .single();
    }

    public List<String> idsWithStatus(DocumentStatus status) {
        return jdbc.sql("SELECT id FROM source_document WHERE status = :status ORDER BY id")
                .param("status", status.name())
                .query((rs, n) -> rs.getString(1))
                .list();
    }

    /** Moves parked documents back to {@code PENDING} with a fresh attempt budget; returns their ids. */
    public List<String> resetFailed() {
        return jdbc.sql("UPDATE source_document SET status = 'PENDING', attempts = 0, updated_at = now()"
                        + " WHERE status = 'FAILED' RETURNING id")
                .query((rs, n) -> rs.getString(1))
                .list();
    }

    public Optional<String> tickerOf(String documentId) {
        return jdbc.sql("SELECT ticker FROM source_document WHERE id = :id")
                .param("id", documentId)
                .query(String.class)
                .optional();
    }
}
