package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.ModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeModelRouter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The single {@link ModelRouter} bean of the RAG tests: delegates to a per-test replaceable fake
 * (scripted reply, or a router that refuses / throws) and records what tier and data class the
 * service asked for.
 */
public final class SwitchableRouter implements ModelRouter {

    private final AtomicReference<ModelRouter> delegate = new AtomicReference<>(FakeModelRouter.withDefaults());
    private final AtomicReference<@Nullable FakeChatModel> chat = new AtomicReference<>();
    private final AtomicReference<@Nullable Tier> lastTier = new AtomicReference<>();
    private final AtomicReference<@Nullable DataClass> lastDataClass = new AtomicReference<>();
    private final AtomicReference<@Nullable DelayingChatModel> slow = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    /** The model replies with this raw text to every call. */
    public FakeChatModel replyWith(String rawReply) {
        FakeChatModel model = new FakeChatModel(rawReply);
        chat.set(model);
        slow.set(null);
        delegate.set(new FakeModelRouter(model, new FakeEmbeddingModel(1536)));
        requests.set(0);
        return model;
    }

    /** As {@link #replyWith}, but every model call takes {@code delay} (a slow provider). */
    public FakeChatModel replyWithDelay(String rawReply, Duration delay) {
        FakeChatModel model = replyWith(rawReply);
        slow.set(new DelayingChatModel(model, delay));
        return model;
    }

    /** The most model calls that ran at the same time since the last {@link #replyWithDelay}. */
    public int maxConcurrentModelCalls() {
        DelayingChatModel model = slow.get();
        return model == null ? 0 : model.maxConcurrentCalls();
    }

    /** Every router request fails with {@code failure}. */
    public void failWith(RuntimeException failure) {
        chat.set(null);
        slow.set(null);
        delegate.set(FakeModelRouter.refusingWith(failure));
        requests.set(0);
    }

    public @Nullable FakeChatModel chatModel() {
        return chat.get();
    }

    public int modelCalls() {
        FakeChatModel model = chat.get();
        return model == null ? 0 : model.callCount();
    }

    public int routerRequests() {
        return requests.get();
    }

    public @Nullable Tier lastTier() {
        return lastTier.get();
    }

    public @Nullable DataClass lastDataClass() {
        return lastDataClass.get();
    }

    @Override
    public ChatClient chatClient(Tier tier, DataClass dataClass) {
        requests.incrementAndGet();
        lastTier.set(tier);
        lastDataClass.set(dataClass);
        DelayingChatModel delayed = slow.get();
        if (delayed != null) {
            // Same behaviour as the fake router's client, plus a provider that takes its time.
            return ChatClient.create(delayed);
        }
        return delegate.get().chatClient(tier, dataClass);
    }

    @Override
    public EmbeddingModel embeddingModel(DataClass dataClass) {
        return delegate.get().embeddingModel(dataClass);
    }
}
