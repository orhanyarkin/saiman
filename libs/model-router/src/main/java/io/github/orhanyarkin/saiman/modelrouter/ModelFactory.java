package io.github.orhanyarkin.saiman.modelrouter;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Creates the raw provider models for a route. The only seam between {@link DefaultModelRouter}
 * and a provider SDK; M3 adds one implementation per provider. Tests plug in fakes here.
 */
public interface ModelFactory {

    ChatModel chatModel(RouterProperties.Route route);

    EmbeddingModel embeddingModel(RouterProperties.Embedding route);
}
