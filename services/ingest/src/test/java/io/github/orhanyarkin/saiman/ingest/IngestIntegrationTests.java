package io.github.orhanyarkin.saiman.ingest;

import io.github.orhanyarkin.saiman.ingest.mkk.FakeMkkServer;
import io.github.orhanyarkin.saiman.ingest.mkk.SyntheticKap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared setup: real Postgres with pgvector (Testcontainers), the fake MKK server with synthetic
 * fixtures and the recording fake embedding model. One cached context for all subclasses; every
 * test starts from an empty schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, TestModelRouterConfiguration.class})
public abstract class IngestIntegrationTests {

    protected static final FakeMkkServer MKK = FakeMkkServer.start(SyntheticKap.CREDENTIALS);

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected RecordingEmbeddingModel embeddings;

    @DynamicPropertySource
    static void mkkProperties(DynamicPropertyRegistry registry) {
        registry.add("saiman.ingest.mkk.base-url", MKK::baseUrl);
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE chunk, dead_letter, source_cursor, source_document CASCADE")
                .update();
        MKK.reset();
        SyntheticKap.load(MKK);
        embeddings.reset();
    }

    protected long count(String table) {
        Long n = jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
        return n;
    }

    protected long countByStatus(String status) {
        Long n = jdbc.sql("SELECT count(*) FROM source_document WHERE status = :s")
                .param("s", status)
                .query(Long.class)
                .single();
        return n;
    }
}
