package io.github.orhanyarkin.saiman.modelrouter;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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
    private final CircuitBreakerRegistry breakers;

    OpenAiModelFactory(RouterProperties.OpenAi credentials) {
        this(credentials, BASE_URL);
    }

    /** Tests point this at a local stub; production code always uses {@link #BASE_URL}. */
    OpenAiModelFactory(RouterProperties.OpenAi credentials, String baseUrl) {
        this(credentials, baseUrl, OpenAiFailoverChatModel.defaultBreakerConfig());
    }

    OpenAiModelFactory(RouterProperties.OpenAi credentials, String baseUrl, CircuitBreakerConfig breakerConfig) {
        this.credentials = credentials;
        this.baseUrl = baseUrl;
        this.breakers = CircuitBreakerRegistry.of(breakerConfig);
    }

    @Override
    public ChatModel chatModel(RouterProperties.Route route) {
        ChatModel primary = single(route);
        RouterProperties.Route backup = route.fallbackRoute();
        if (backup == null) {
            return primary;
        }
        // one breaker per route (primary -> backup pair), created with the model so its state lives as long as it
        var breaker = breakers.circuitBreaker(route.model() + "->" + backup.model());
        return new OpenAiFailoverChatModel(primary, single(backup), breaker);
    }

    private ChatModel single(RouterProperties.Route route) {
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
                .reasoningEffort(route.reasoningEffort())
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

        /**
         * The route's OpenAI options. Spring AI builds every request from {@code getOptions().mutate()}; the
         * default (plain {@code ChatOptions}) would silently drop the tool callbacks of a tool-calling run.
         */
        @Override
        public ChatOptions getOptions() {
            return delegate.get().getOptions();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return delegate.get().call(openAiPrompt(prompt));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> delegate.get().stream(withStreamUsage(openAiPrompt(prompt))));
        }

        /** Rebuilds the request from the route's own options; see {@link #wireOptions}. */
        private Prompt openAiPrompt(Prompt prompt) {
            if (!(delegate.get().getOptions() instanceof OpenAiChatOptions route)) {
                return prompt;
            }
            return new Prompt(prompt.getInstructions(), wireOptions(route, prompt.getOptions()));
        }
    }

    /**
     * The options that go on the wire: always the route's own, plus an allowlist copied from the caller's.
     *
     * <p>Spring AI's OpenAI model uses a prompt's options as-is (no merge with the model defaults), so taking the
     * caller's options would let a caller drop {@code max_completion_tokens} or the reasoning effort, ask for
     * {@code n > 1}, add body fields or headers, or redirect the key, all of which the cost estimate does not see.
     * The route's model, base URL, API key, limits, reasoning effort and usage reporting therefore always win.
     *
     * <p>Copied from the caller: temperature; tool callbacks and tool context (tool calling breaks silently
     * without them); the output schema and response format (they only shape the answer). The completion-token
     * limit is the caller's only if it is lower than the route's. Rejected with {@link IllegalArgumentException}:
     * {@code n > 1}, extra body fields, custom headers, and a base URL or API key other than the route's.
     */
    static OpenAiChatOptions wireOptions(OpenAiChatOptions route, @Nullable ChatOptions requested) {
        var builder = route.mutate();
        if (requested == null) {
            return builder.build();
        }
        if (requested instanceof OpenAiChatOptions openAi) {
            rejectOverrides(route, openAi);
            if (openAi.getResponseFormat() != null) {
                builder.responseFormat(openAi.getResponseFormat());
            }
        }
        if (requested.getTemperature() != null) {
            builder.temperature(requested.getTemperature());
        }
        Integer limit = requestedLimit(requested);
        Integer routeLimit = route.getMaxCompletionTokens();
        if (limit != null && limit > 0 && (routeLimit == null || limit < routeLimit)) {
            builder.maxCompletionTokens(limit);
        }
        if (requested instanceof ToolCallingChatOptions tooling) {
            if (tooling.getToolCallbacks() != null
                    && !tooling.getToolCallbacks().isEmpty()) {
                builder.toolCallbacks(tooling.getToolCallbacks());
            }
            if (tooling.getToolContext() != null && !tooling.getToolContext().isEmpty()) {
                builder.toolContext(tooling.getToolContext());
            }
        }
        if (requested instanceof StructuredOutputChatOptions structured && structured.getOutputSchema() != null) {
            builder.outputSchema(structured.getOutputSchema());
        }
        return builder.build();
    }

    private static @Nullable Integer requestedLimit(ChatOptions requested) {
        if (requested instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null) {
            return openAi.getMaxCompletionTokens();
        }
        return requested.getMaxTokens();
    }

    private static void rejectOverrides(OpenAiChatOptions route, OpenAiChatOptions requested) {
        if (requested.getN() != null && requested.getN() > 1) {
            throw new IllegalArgumentException(
                    "Request option 'n' above 1 is not allowed: the cost estimate assumes one");
        }
        if (requested.getExtraBody() != null && !requested.getExtraBody().isEmpty()) {
            throw new IllegalArgumentException("Request option 'extraBody' is not allowed");
        }
        if (requested.getCustomHeaders() != null
                && !requested.getCustomHeaders().isEmpty()) {
            throw new IllegalArgumentException("Request option 'customHeaders' is not allowed");
        }
        if (requested.getBaseUrl() != null && !requested.getBaseUrl().equals(route.getBaseUrl())) {
            throw new IllegalArgumentException("Request option 'baseUrl' cannot differ from the route's");
        }
        if (requested.getApiKey() != null && !requested.getApiKey().equals(route.getApiKey())) {
            throw new IllegalArgumentException("Request option 'apiKey' cannot differ from the route's");
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
