package io.github.orhanyarkin.saiman.ingest.retrieval;

import io.github.orhanyarkin.saiman.shared.retrieval.IndexedTicker;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read side of the corpus (plain SQL through {@link JdbcClient}). Both legs only see chunks of
 * {@code INDEXED} documents: superseded, blocked, pending and failed ones never surface.
 */
@Repository
public class RetrievalRepository {

    static final int LEG_SIZE = 40;
    private static final int MAX_TERMS = 32;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private final JdbcClient jdbc;

    public RetrievalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Relaxed iterative scans let a filtered HNSW query keep scanning until it has enough rows. */
    public void enableIterativeScan() {
        jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
    }

    public List<String> vectorLeg(float[] embedding, List<String> tickers) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id FROM chunk c JOIN source_document d ON d.id = c.document_id
                WHERE d.status = 'INDEXED'
                """);
        if (!tickers.isEmpty()) {
            sql.append(" AND c.ticker IN (:tickers)");
        }
        sql.append(" ORDER BY c.embedding <=> CAST(:vec AS vector) LIMIT ").append(LEG_SIZE);
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("vec", vectorLiteral(embedding));
        if (!tickers.isEmpty()) {
            spec = spec.param("tickers", tickers);
        }
        return spec.query((rs, n) -> rs.getString(1)).list();
    }

    /**
     * Full-text leg. Words are OR-ed (a natural-language question rarely has all its words in one
     * chunk; {@code ts_rank_cd} then favours chunks matching more of them), lower-cased with the
     * Turkish ICU collation so that {@code I}/{@code ı} and {@code İ}/{@code i} match, and run
     * through the Turkish text-search configuration.
     */
    public List<String> lexicalLeg(String query, List<String> tickers) {
        String orQuery = orQuery(query);
        if (orQuery.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                WITH q AS (SELECT websearch_to_tsquery('turkish', lower(CAST(:q AS text) COLLATE "tr-TR-x-icu")) AS tsq)
                SELECT c.id FROM chunk c JOIN source_document d ON d.id = c.document_id, q
                WHERE d.status = 'INDEXED' AND c.content_tsv @@ q.tsq
                """);
        if (!tickers.isEmpty()) {
            sql.append(" AND c.ticker IN (:tickers)");
        }
        sql.append(" ORDER BY ts_rank_cd(c.content_tsv, q.tsq) DESC, c.id LIMIT ")
                .append(LEG_SIZE);
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("q", orQuery);
        if (!tickers.isEmpty()) {
            spec = spec.param("tickers", tickers);
        }
        return spec.query((rs, n) -> rs.getString(1)).list();
    }

    /** Loads chunks by id (only from {@code INDEXED} documents). */
    public Map<String, RetrievedChunk> load(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(SELECT_CHUNK + " WHERE d.status = 'INDEXED' AND c.id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> chunk(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(RetrievedChunk::chunkId, chunk -> chunk));
    }

    public Optional<RetrievedChunk> find(String chunkId) {
        return jdbc.sql(SELECT_CHUNK + " WHERE d.status = 'INDEXED' AND c.id = :id")
                .param("id", chunkId)
                .query((rs, n) -> chunk(rs))
                .optional();
    }

    public List<IndexedTicker> tickers() {
        return jdbc.sql("""
                        SELECT d.ticker, count(DISTINCT d.id) AS documents, count(c.id) AS chunks
                        FROM source_document d LEFT JOIN chunk c ON c.document_id = d.id
                        WHERE d.status = 'INDEXED'
                        GROUP BY d.ticker HAVING count(c.id) > 0 ORDER BY d.ticker
                        """)
                .query((rs, n) ->
                        new IndexedTicker(rs.getString("ticker"), rs.getLong("documents"), rs.getLong("chunks")))
                .list();
    }

    /** Newest publication time in the indexed corpus; the epoch for an empty corpus. */
    public Instant corpusWatermark() {
        return jdbc.sql("SELECT max(published_at) FROM source_document WHERE status = 'INDEXED'")
                .query((rs, n) -> {
                    OffsetDateTime at = rs.getObject(1, OffsetDateTime.class);
                    return at == null ? Instant.EPOCH : at.toInstant();
                })
                .single();
    }

    private static final String SELECT_CHUNK = """
            SELECT c.id, c.content, d.ticker, d.title, d.source_url, d.published_at, d.retrieved_at
            FROM chunk c JOIN source_document d ON d.id = c.document_id
            """;

    private static RetrievedChunk chunk(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RetrievedChunk(
                rs.getString("id"),
                rs.getString("ticker"),
                "kap",
                rs.getString("title"),
                rs.getString("source_url"),
                rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                rs.getObject("retrieved_at", OffsetDateTime.class).toInstant(),
                rs.getString("content"),
                0.0,
                null,
                null);
    }

    static String orQuery(String query) {
        Matcher matcher = WORD.matcher(query);
        StringBuilder out = new StringBuilder();
        int terms = 0;
        while (matcher.find() && terms < MAX_TERMS) {
            String word = matcher.group();
            // "or" / "and" / "not" would be read as websearch operators; quote nothing, just skip.
            if (word.equalsIgnoreCase("or")) {
                continue;
            }
            if (terms > 0) {
                out.append(" or ");
            }
            out.append(word);
            terms++;
        }
        return out.toString();
    }

    static String vectorLiteral(float[] embedding) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(embedding[i]);
        }
        return out.append(']').toString();
    }
}
