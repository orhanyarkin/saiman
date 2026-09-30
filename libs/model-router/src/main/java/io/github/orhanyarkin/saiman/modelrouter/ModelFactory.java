package io.github.orhanyarkin.saiman.modelrouter;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Creates the raw provider models for a route. The only seam between {@link DefaultModelRouter}
 * and a provider SDK. Tests plug in fakes here.
 *
 * <p><b>Provider boundary.</b> No class outside the provider's adapter ({@code OpenAiModelFactory},
 * {@code OpenAiFailoverChatModel}) may import or mention a provider SDK or Spring AI provider type
 * (for OpenAI: {@code com.openai.*}, {@code org.springframework.ai.openai.*}); routing, costing, caps,
 * advisors and configuration speak only {@code ChatModel}, {@code ChatOptions} and the router's own
 * records. A Gemini or DeepSeek adapter is then one new factory class plus route configuration.
 * (Checked by review and by grep: {@code grep -rl 'com.openai\|ai.openai' src/main/java} lists only
 * the adapter files and this comment.)
 */
public interface ModelFactory {

    ChatModel chatModel(RouterProperties.Route route);

    EmbeddingModel embeddingModel(RouterProperties.Embedding route);
}
