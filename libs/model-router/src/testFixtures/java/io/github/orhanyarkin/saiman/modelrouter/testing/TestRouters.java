package io.github.orhanyarkin.saiman.modelrouter.testing;

import io.github.orhanyarkin.saiman.modelrouter.CostGuard;
import io.github.orhanyarkin.saiman.modelrouter.DefaultModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.InMemoryCostGuard;
import io.github.orhanyarkin.saiman.modelrouter.ModelFactory;
import io.github.orhanyarkin.saiman.modelrouter.RouterMetrics;
import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Builds the real {@link DefaultModelRouter} (data-class policy, daily cap, cost accounting) over
 * fake models, so a service can test the actual cap logic without a key or a network.
 */
public final class TestRouters {

    private TestRouters() {}

    /** Real router with the library's default routes/prices over the given models and guard. */
    public static DefaultModelRouter over(ChatModel chat, EmbeddingModel embedding, CostGuard guard) {
        return over(RouterProperties.defaults(), chat, embedding, guard);
    }

    public static DefaultModelRouter over(
            RouterProperties properties, ChatModel chat, EmbeddingModel embedding, CostGuard guard) {
        ModelFactory factory = new ModelFactory() {
            @Override
            public ChatModel chatModel(RouterProperties.Route route) {
                return chat;
            }

            @Override
            public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                return embedding;
            }
        };
        return new DefaultModelRouter(properties, factory, guard, RouterMetrics.NOOP);
    }

    /** Real router with an in-memory daily cap of {@code capUsdMicros}. */
    public static DefaultModelRouter overInMemory(ChatModel chat, EmbeddingModel embedding, long capUsdMicros) {
        return over(
                RouterProperties.defaults().withDailyCapUsdMicros(capUsdMicros),
                chat,
                embedding,
                new InMemoryCostGuard(capUsdMicros, Clock.systemUTC()));
    }
}
