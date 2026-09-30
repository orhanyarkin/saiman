package io.github.orhanyarkin.saiman.modelrouter;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The router (ADR-0011). The data-class check is plain code and runs before a client is handed
 * out, so a refused request never reaches a provider; the daily cap (by reservation) and metrics
 * are advisors on the returned client (and a decorator on the embedding model).
 *
 * <p>The constructor validates the configuration and fails startup on: an unsupported provider, a
 * missing price, a chat route without a positive {@code max-completion-tokens}, a route that allows
 * {@link DataClass#SENSITIVE}, a negative retry count.
 */
public final class DefaultModelRouter implements ModelRouter {

    private static final String PROVIDER = "openai";

    private final CostGuard guard;
    private final RouterMetrics metrics;
    private final Map<Tier, ChatModel> chatModels = new EnumMap<>(Tier.class);
    private final Map<Tier, RouterProperties.Route> routes = new EnumMap<>(Tier.class);
    private final Map<Tier, RouteCosting> costings = new EnumMap<>(Tier.class);
    private final RouterProperties.Embedding embeddingRoute;
    private final EmbeddingModel embeddingModel;

    public DefaultModelRouter(
            RouterProperties properties, ModelFactory factory, CostGuard guard, RouterMetrics metrics) {
        this.guard = guard;
        this.metrics = metrics;
        int maxRetries = properties.openai().maxRetries();
        if (maxRetries < 0) {
            throw new IllegalStateException("saiman.router.openai.max-retries must not be negative");
        }
        for (Tier tier : Tier.values()) {
            RouterProperties.Route route = properties.routes().get(tier);
            if (route == null) {
                throw new IllegalStateException("saiman.router.routes has no route for tier " + tier);
            }
            requireProvider(route.provider(), "route for tier " + tier);
            requireNoSensitive(route.allowedDataClasses(), "tier " + tier);
            if (route.maxCompletionTokens() <= 0) {
                throw new IllegalStateException(
                        "saiman.router.routes." + tierLabel(tier) + ".max-completion-tokens must be positive");
            }
            routes.put(tier, route);
            costings.put(
                    tier,
                    new RouteCosting(
                            tierLabel(tier),
                            route.model(),
                            route.maxCompletionTokens(),
                            maxRetries,
                            properties.prices()));
            chatModels.put(tier, factory.chatModel(route));
        }
        RouterProperties.Embedding embedding = properties.embedding();
        if (embedding == null) {
            throw new IllegalStateException("saiman.router.embedding is missing");
        }
        this.embeddingRoute = embedding;
        requireProvider(embedding.provider(), "embedding route");
        requireNoSensitive(embedding.allowedDataClasses(), "the embedding route");
        if (embedding.dimensions() <= 0) {
            throw new IllegalStateException("saiman.router.embedding.dimensions must be positive");
        }
        RouteCosting embeddingCosting =
                new RouteCosting("embedding", embedding.model(), 0, maxRetries, properties.prices());
        this.embeddingModel = new CostGuardedEmbeddingModel(
                factory.embeddingModel(embedding), embedding.dimensions(), embeddingCosting, guard, metrics);
    }

    @Override
    public ChatClient chatClient(Tier tier, DataClass dataClass) {
        RouterProperties.Route route = routes.get(tier);
        ChatModel model = chatModels.get(tier);
        RouteCosting costing = costings.get(tier);
        if (route == null || model == null || costing == null) {
            throw new IllegalStateException("no route for tier " + tier);
        }
        requireAllowed(route.allowedDataClasses(), dataClass, "tier " + tier);
        return ChatClient.builder(model)
                .defaultAdvisors(new CostAdvisor(costing, guard, metrics))
                .build();
    }

    @Override
    public EmbeddingModel embeddingModel(DataClass dataClass) {
        requireAllowed(embeddingRoute.allowedDataClasses(), dataClass, "the embedding route");
        return embeddingModel;
    }

    static String tierLabel(Tier tier) {
        return tier.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void requireAllowed(Set<DataClass> allowed, DataClass requested, String routeName) {
        if (!allowed.contains(requested)) {
            throw new DataClassViolationException(
                    "data class " + requested + " is not allowed on " + routeName + " (allowed: " + allowed + ")");
        }
    }

    private static void requireNoSensitive(Set<DataClass> allowed, String routeName) {
        if (allowed.contains(DataClass.SENSITIVE)) {
            throw new IllegalStateException("SENSITIVE data must never be allowed on " + routeName + " (ADR-0003)");
        }
    }

    private static void requireProvider(String provider, String what) {
        if (!PROVIDER.equals(provider)) {
            throw new IllegalStateException("unsupported provider for " + what + ": only '" + PROVIDER + "' exists");
        }
    }
}
