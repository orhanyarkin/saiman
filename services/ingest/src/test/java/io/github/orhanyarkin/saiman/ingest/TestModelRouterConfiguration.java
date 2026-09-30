package io.github.orhanyarkin.saiman.ingest;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.ModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** A {@link ModelRouter} that hands out the recording fake embedding model: no key, no network. */
@TestConfiguration(proxyBeanMethods = false)
public class TestModelRouterConfiguration {

    @Bean
    RecordingEmbeddingModel recordingEmbeddingModel() {
        return new RecordingEmbeddingModel();
    }

    @Bean
    ModelRouter modelRouter(RecordingEmbeddingModel embeddings) {
        return new ModelRouter() {
            @Override
            public ChatClient chatClient(Tier tier, DataClass dataClass) {
                throw new UnsupportedOperationException("ingest does not chat");
            }

            @Override
            public EmbeddingModel embeddingModel(DataClass dataClass) {
                return embeddings;
            }
        };
    }
}
