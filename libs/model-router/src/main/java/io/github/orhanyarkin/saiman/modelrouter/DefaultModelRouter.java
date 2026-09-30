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
 * out, so a refused request never reaches a provider; the daily cap and metrics are advisors on the
 * returned client (and a decorator on the embedding model).
 */
public final class DefaultModelRouter implements ModelRouter {

    private static final String PROVIDER = "openai";

    private final RouterProperties properties;
    private final CostGuard guard;
    private final RouterMetrics metrics;
    private final Map<Tier, ChatModel> chatModels = new EnumMap<>(Tier.class);
    private final Map<Tier, RouterProperties.Route> routes = new EnumMap<>(Tier.class);
    private final RouterProperties.Embedding embeddingRoute;
    private final EmbeddingModel embeddingModel;

    public DefaultModelRouter(
            RouterProperties properties, ModelFactory factory, CostGuard guard, RouterMetrics metrics) {
        this.properties = properties;
        this.guard = guard;
        this.metrics = metrics;
        for (Tier tier : Tier.values()) {
            RouterProperties.Route route = properties.routes().get(tier);
            if (route == null) {
                throw new IllegalStateException("saiman.router.routes has no route for tier " + tier);
            }
            requireProvider(route.provider(), "route for tier " + tier);
            requirePrice(route.model(), "tier " + tier);
            routes.put(tier, route);
            chatModels.put(tier, factory.chatModel(route));
        }
        RouterProperties.Embedding embedding = properties.embedding();
        if (embedding == null) {
            throw new IllegalStateException("saiman.router.embedding is missing");
        }
        this.embeddingRoute = embedding;
        requireProvider(embedding.provider(), "embedding route");
        requirePrice(embedding.model(), "the embedding route");
        if (embedding.dimensions() <= 0) {
            throw new IllegalStateException("saiman.router.embedding.dimensions must be positive");
        }
        this.embeddingModel = new CostGuardedEmbeddingModel(
                factory.embeddingModel(embedding), embedding.dimensions(), price(embedding.model()), guard, metrics);
    }

    @Override
    public ChatClient chatClient(Tier tier, DataClass dataClass) {
        RouterProperties.Route route = routes.get(tier);
        ChatModel model = chatModels.get(tier);
        if (route == null || model == null) {
            throw new IllegalStateException("no route for tier " + tier);
        }
        requireAllowed(route.allowedDataClasses(), dataClass, "tier " + tier);
        CostAdvisor advisor = new CostAdvisor(tierLabel(tier), price(route.model()), guard, metrics);
        return ChatClient.builder(model).defaultAdvisors(advisor).build();
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

    private static void requireProvider(String provider, String what) {
        if (!PROVIDER.equals(provider)) {
            throw new IllegalStateException("unsupported provider for " + what + ": only '" + PROVIDER + "' exists");
        }
    }

    private void requirePrice(String model, String what) {
        if (!properties.prices().containsKey(model)) {
            throw new IllegalStateException("saiman.router.prices has no price for the model of " + what);
        }
    }

    private RouterProperties.Price price(String model) {
        RouterProperties.Price price = properties.prices().get(model);
        if (price == null) {
            throw new IllegalStateException("saiman.router.prices has no price for a routed model");
        }
        return price;
    }
}
