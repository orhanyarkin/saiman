package io.github.orhanyarkin.saiman.modelrouter;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The only way Saiman code obtains a chat or embedding model (CLAUDE.md rule 6, ADR-0011).
 * Implementations enforce the data-classification policy, the daily USD cap and record token/USD
 * metrics; callers never see a provider SDK.
 */
public interface ModelRouter {

    /**
     * A chat client for {@code tier}, with cost-cap and cost-metric advisors installed.
     *
     * @throws DataClassViolationException if the route for {@code tier} does not allow {@code dataClass}
     */
    ChatClient chatClient(Tier tier, DataClass dataClass);

    /**
     * The embedding model, wrapped with the cost cap and metrics. Its dimension is fixed by the
     * database schema (1536 for {@code text-embedding-3-small}).
     *
     * @throws DataClassViolationException if the embedding route does not allow {@code dataClass}
     */
    EmbeddingModel embeddingModel(DataClass dataClass);
}
