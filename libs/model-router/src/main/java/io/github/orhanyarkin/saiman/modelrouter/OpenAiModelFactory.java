package io.github.orhanyarkin.saiman.modelrouter;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import reactor.core.publisher.Flux;

/**
 * Builds the OpenAI models by hand from {@code spring-ai-openai} (no Boot starter, ADR-0011).
 *
 * <p>The models are created on first use, not at startup: without an API key the application must
 * still start (the router fails closed on the first call, with a message that never contains the
 * key), and the OpenAI client is not built at all until a call needs it.
 */
final class OpenAiModelFactory implements ModelFactory {

    /**
     * Fixed on purpose, on both options: without it Spring AI's OpenAI setup falls back to the {@code
     * OPENAI_BASE_URL} / {@code AZURE_OPENAI_BASE_URL} environment variables and would send the API
     * key and prompts wherever they point. There is no property to change it.
     */
    static final String BASE_URL = "https://api.openai.com/v1";

    private final RouterProperties.OpenAi credentials;
    private final String baseUrl;

    OpenAiModelFactory(RouterProperties.OpenAi credentials) {
        this(credentials, BASE_URL);
    }

    /** Tests point this at a local stub; production code always uses {@link #BASE_URL}. */
    OpenAiModelFactory(RouterProperties.OpenAi credentials, String baseUrl) {
        this.credentials = credentials;
        this.baseUrl = baseUrl;
    }

    @Override
    public ChatModel chatModel(RouterProperties.Route route) {
        return new LazyChatModel(
                () -> OpenAiChatModel.builder().options(chatOptions(route)).build());
    }

    @Override
    public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
        return new LazyEmbeddingModel(
                route.dimensions(),
                () -> OpenAiEmbeddingModel.builder()
                        .options(embeddingOptions(route))
                        .build());
    }

    OpenAiChatOptions chatOptions(RouterProperties.Route route) {
        return OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .model(route.model())
                .apiKey(requireApiKey())
                .maxCompletionTokens(route.maxCompletionTokens())
                .maxRetries(credentials.maxRetries())
                .timeout(credentials.timeout())
                // explicit: streamed responses must carry token usage or the cost cannot be counted
                .streamUsage(true)
                .build();
    }

    OpenAiEmbeddingOptions embeddingOptions(RouterProperties.Embedding route) {
        return OpenAiEmbeddingOptions.builder()
                .baseUrl(baseUrl)
                .model(route.model())
                .dimensions(route.dimensions())
                .apiKey(requireApiKey())
                .maxRetries(credentials.maxRetries())
                .timeout(credentials.timeout())
                .build();
    }

    /** The key with surrounding whitespace removed (a hand-made secret file ends in a newline). */
    private String requireApiKey() {
        String key = credentials.apiKey();
        if (key == null || key.isBlank()) {
            throw new RequestNotSentException("OpenAI API key is not configured: set the 'openai_api_key' property"
                    + " (configtree file) or 'saiman.router.openai.api-key'");
        }
        return key.strip();
    }

    /** Forces usage reporting on streams even if the caller supplied its own OpenAI options. */
    static Prompt withStreamUsage(Prompt prompt) {
        if (prompt.getOptions() instanceof OpenAiChatOptions options) {
            return new Prompt(
                    prompt.getInstructions(), options.mutate().streamUsage(true).build());
        }
        return prompt;
    }

    /** Memoising holder; the supplier runs at most once successfully. */
    private static final class Lazy<T> {
        private final Supplier<T> supplier;
        private volatile @Nullable T value;

        Lazy(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            T current = value;
            if (current == null) {
                synchronized (this) {
                    current = value;
                    if (current == null) {
                        current = supplier.get();
                        value = current;
                    }
                }
            }
            return current;
        }
    }

    private static final class LazyChatModel implements ChatModel {
        private final Lazy<ChatModel> delegate;

        LazyChatModel(Supplier<ChatModel> supplier) {
            this.delegate = new Lazy<>(supplier);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return delegate.get().call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> delegate.get().stream(withStreamUsage(prompt)));
        }
    }

    private static final class LazyEmbeddingModel implements EmbeddingModel {
        private final int dimensions;
        private final Lazy<EmbeddingModel> delegate;

        LazyEmbeddingModel(int dimensions, Supplier<EmbeddingModel> supplier) {
            this.dimensions = dimensions;
            this.delegate = new Lazy<>(supplier);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return delegate.get().call(request);
        }

        @Override
        public float[] embed(Document document) {
            return delegate.get().embed(document);
        }

        @Override
        public int dimensions() {
            return dimensions;
        }
    }
}
