package io.github.orhanyarkin.saiman.modelrouter;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code saiman.router.*} configuration. Defaults come from the library's {@code
 * config/router/routes.yaml} and {@code prices.yaml} (see {@link RouterPropertiesBinder}).
 *
 * <p>Deliberately carries no Bean Validation annotations: Boot's bind-failure report prints the
 * rejected value of every failed constraint, which could put the API key in a startup log. {@link
 * DefaultModelRouter} validates in plain code, with messages that never echo values that could be
 * secret.
 *
 * @param routes tier to route
 * @param embedding the embedding route
 * @param dailyCapUsdMicros global daily cap in USD micro-dollars (700000 = $0.70)
 * @param prices price per model id
 * @param openai provider credentials and client limits
 * @param costGuard {@code memory} to accept a per-process daily cap when no Valkey is configured;
 *     absent otherwise (see {@link ModelRouterAutoConfiguration})
 */
@ConfigurationProperties(prefix = "saiman.router")
public record RouterProperties(
        Map<Tier, Route> routes,
        @Nullable Embedding embedding,
        @DefaultValue("700000") long dailyCapUsdMicros,
        Map<String, Price> prices,
        OpenAi openai,
        @Nullable String costGuard) {

    /** The library defaults only (no application overrides): for tests and fixtures. */
    public static RouterProperties defaults() {
        return RouterPropertiesBinder.bind(new org.springframework.core.env.StandardEnvironment());
    }

    /** A copy with a different daily cap. */
    public RouterProperties withDailyCapUsdMicros(long cap) {
        return new RouterProperties(routes, embedding, cap, prices, openai, costGuard);
    }

    public RouterProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        openai = openai == null ? new OpenAi(null, 1, Duration.ofSeconds(30)) : openai;
    }

    /**
     * A chat route.
     *
     * @param provider provider key; only {@code openai} exists in M2
     * @param model provider model id
     * @param maxCompletionTokens hard output limit sent with every request; required, {@code > 0},
     *     and the basis of the worst-case cost reserved before each call
     * @param allowedDataClasses data classes the provider may see (ADR-0003)
     * @param region hosting region, informational
     * @param reasoningEffort OpenAI {@code reasoning_effort} for reasoning models ({@code minimal}, {@code low}, ...);
     *     absent for models that do not reason. Hidden reasoning is billed and counts against the token limit.
     */
    public record Route(
            String provider,
            String model,
            int maxCompletionTokens,
            Set<DataClass> allowedDataClasses,
            String region,
            @Nullable String reasoningEffort) {
        @ConstructorBinding
        public Route {
            allowedDataClasses = allowedDataClasses == null ? Set.of() : Set.copyOf(allowedDataClasses);
        }

        /** A route without a reasoning effort. */
        public Route(
                String provider,
                String model,
                int maxCompletionTokens,
                Set<DataClass> allowedDataClasses,
                String region) {
            this(provider, model, maxCompletionTokens, allowedDataClasses, region, null);
        }
    }

    /**
     * The embedding route.
     *
     * @param dimensions vector size; fixed by the database schema (1536)
     */
    public record Embedding(
            String provider, String model, int dimensions, Set<DataClass> allowedDataClasses, String region) {
        public Embedding {
            allowedDataClasses = allowedDataClasses == null ? Set.of() : Set.copyOf(allowedDataClasses);
        }
    }

    /**
     * Price of one model, in USD micro-dollars per million tokens.
     *
     * @param inputUsdMicrosPerMtok price per million input tokens
     * @param outputUsdMicrosPerMtok price per million output tokens
     */
    public record Price(long inputUsdMicrosPerMtok, long outputUsdMicrosPerMtok) {}

    /**
     * OpenAI credentials and client limits.
     *
     * @param apiKey the key; {@code null} or blank means "not configured" and the first call fails
     *     closed
     * @param maxRetries SDK retries per call; every retry can be billed, so the worst-case estimate
     *     multiplies by {@code 1 + maxRetries}
     * @param timeout request timeout
     */
    public record OpenAi(
            @Nullable String apiKey,
            @DefaultValue("1") int maxRetries,
            @DefaultValue("30s") Duration timeout) {

        public OpenAi {
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        OpenAi withApiKey(@Nullable String key) {
            return new OpenAi(key, maxRetries, timeout);
        }

        /** Redacts the key: never let a debug or bind-failure report print it. */
        @Override
        public String toString() {
            return "OpenAi[apiKey=" + (hasApiKey() ? "REDACTED" : "unset") + ", maxRetries=" + maxRetries + ", timeout="
                    + timeout + "]";
        }
    }
}
