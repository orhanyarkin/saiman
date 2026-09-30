package io.github.orhanyarkin.saiman.modelrouter;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * @param openai provider credentials
 */
@ConfigurationProperties(prefix = "saiman.router")
public record RouterProperties(
        Map<Tier, Route> routes,
        @Nullable Embedding embedding,
        @DefaultValue("700000") long dailyCapUsdMicros,
        Map<String, Price> prices,
        OpenAi openai) {

    /** The library defaults only (no application overrides): for tests and fixtures. */
    public static RouterProperties defaults() {
        return RouterPropertiesBinder.bind(new org.springframework.core.env.StandardEnvironment());
    }

    /** A copy with a different daily cap. */
    public RouterProperties withDailyCapUsdMicros(long cap) {
        return new RouterProperties(routes, embedding, cap, prices, openai);
    }

    public RouterProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        openai = openai == null ? new OpenAi(null) : openai;
    }

    /**
     * A chat route.
     *
     * @param provider provider key; only {@code openai} exists in M2
     * @param model provider model id
     * @param allowedDataClasses data classes the provider may see (ADR-0003)
     * @param region hosting region, informational
     */
    public record Route(String provider, String model, Set<DataClass> allowedDataClasses, String region) {
        public Route {
            allowedDataClasses = allowedDataClasses == null ? Set.of() : Set.copyOf(allowedDataClasses);
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
     * OpenAI credentials.
     *
     * @param apiKey the key; {@code null} or blank means "not configured" and the first call fails
     *     closed
     */
    public record OpenAi(@Nullable String apiKey) {

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Redacts the key: never let a debug or bind-failure report print it. */
        @Override
        public String toString() {
            return "OpenAi[apiKey=" + (hasApiKey() ? "REDACTED" : "unset") + "]";
        }
    }
}
