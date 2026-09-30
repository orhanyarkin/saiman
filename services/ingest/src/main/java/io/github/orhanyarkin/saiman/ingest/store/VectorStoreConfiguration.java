package io.github.orhanyarkin.saiman.ingest.store;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.chunking.DisclosureChunker;
import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.ModelRouter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The write side of the corpus: Spring AI's {@link PgVectorStore} on {@code ingest.chunk}. The
 * schema comes from Flyway ({@code initialize-schema=false}); ids are TEXT so chunk ids stay
 * human-readable ({@code kap:<index>:<nnnn>}). Disclosures are public data, so the embedding route
 * is requested with {@link DataClass#PUBLIC}.
 */
@Configuration(proxyBeanMethods = false)
class VectorStoreConfiguration {

    static final int DIMENSIONS = 1536;

    @Bean
    VectorStore chunkVectorStore(JdbcTemplate jdbcTemplate, ModelRouter router) {
        return PgVectorStore.builder(jdbcTemplate, router.embeddingModel(DataClass.PUBLIC))
                .schemaName("ingest")
                .vectorTableName("chunk")
                .idType(PgVectorStore.PgIdType.TEXT)
                .dimensions(DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(false)
                .build();
    }

    @Bean
    DisclosureChunker disclosureChunker(IngestProperties properties) {
        return new DisclosureChunker(properties.chunk().targetTokens());
    }
}
