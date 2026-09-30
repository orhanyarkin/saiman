package io.github.orhanyarkin.saiman.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import org.junit.jupiter.api.Test;

/** The {@code tr-TR-x-icu} collation exists in the pgvector image and folds Turkish casing correctly. */
class TurkishCollationTests extends IngestIntegrationTests {

    private String lower(String value) {
        return jdbc.sql("SELECT lower(CAST(:v AS text) COLLATE \"tr-TR-x-icu\")")
                .param("v", value)
                .query(String.class)
                .single();
    }

    @Test
    void dottedAndDotlessIAreLoweredTheTurkishWay() {
        assertThat(lower("İSTANBUL")).isEqualTo("istanbul");
        assertThat(lower("ISPARTA")).isEqualTo("ısparta");
    }

    @Test
    void migrationCreatedGeneratedColumnsAndIndexes() {
        assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname = 'ingest'"
                                + " AND indexname IN ('chunk_embedding_hnsw_idx', 'chunk_content_tsv_idx')")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }
}
