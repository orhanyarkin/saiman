package io.github.orhanyarkin.saiman.modelrouter.testing;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Scripted chat model for tests: returns a fixed answer and reports a configurable token usage, so
 * cost accounting can be exercised without a provider. Counts calls and keeps the last prompt.
 */
public final class FakeChatModel implements ChatModel {

    private final String answer;
    private final int promptTokens;
    private final int completionTokens;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<@Nullable Prompt> lastPrompt = new AtomicReference<>();

    public FakeChatModel(String answer) {
        this(answer, 10, 5);
    }

    public FakeChatModel(String answer, int promptTokens, int completionTokens) {
        this.answer = answer;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public int callCount() {
        return calls.get();
    }

    public @Nullable Prompt lastPrompt() {
        return lastPrompt.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        lastPrompt.set(prompt);
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(answer))))
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build())
                .build();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> Flux.just(call(prompt)));
    }
}
