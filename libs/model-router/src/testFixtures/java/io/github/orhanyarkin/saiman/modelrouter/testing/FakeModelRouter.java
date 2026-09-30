package io.github.orhanyarkin.saiman.modelrouter.testing;

import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.ModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * A {@link ModelRouter} backed by {@link FakeChatModel} and {@link FakeEmbeddingModel}: for ingest
 * and seller-api tests that must never need a key or a network. It applies no data-class policy or
 * cap; those are tested against the real router.
 */
public final class FakeModelRouter implements ModelRouter {

    private final FakeChatModel chatModel;
    private final FakeEmbeddingModel embeddingModel;
    private final @Nullable RuntimeException refusal;

    public FakeModelRouter(FakeChatModel chatModel, FakeEmbeddingModel embeddingModel) {
        this(chatModel, embeddingModel, null);
    }

    private FakeModelRouter(
            FakeChatModel chatModel, FakeEmbeddingModel embeddingModel, @Nullable RuntimeException refusal) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.refusal = refusal;
    }

    /**
     * A router that refuses every request with {@code refusal}, e.g. a {@code
     * DailyCapExceededException} or {@code DataClassViolationException}, to test how a caller
     * degrades (replay mode, error response). The fakes it hands out are never reached.
     */
    public static FakeModelRouter refusingWith(RuntimeException refusal) {
        return new FakeModelRouter(new FakeChatModel("unreachable"), new FakeEmbeddingModel(1536), refusal);
    }

    /** 1536-dimension embeddings and a canned answer. */
    public static FakeModelRouter withDefaults() {
        return new FakeModelRouter(new FakeChatModel("fake answer"), new FakeEmbeddingModel(1536));
    }

    public FakeChatModel chatModel() {
        return chatModel;
    }

    public FakeEmbeddingModel fakeEmbeddingModel() {
        return embeddingModel;
    }

    @Override
    public ChatClient chatClient(Tier tier, DataClass dataClass) {
        throwIfRefusing();
        return ChatClient.create(chatModel);
    }

    @Override
    public EmbeddingModel embeddingModel(DataClass dataClass) {
        throwIfRefusing();
        return embeddingModel;
    }

    private void throwIfRefusing() {
        if (refusal != null) {
            throw refusal;
        }
    }
}
