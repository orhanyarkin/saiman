package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * A {@link ChatModel} that holds every call for a while (a slow provider) and records how many
 * calls were running at the same time.
 */
final class DelayingChatModel implements ChatModel {

    private final FakeChatModel inner;
    private final Duration delay;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger maxRunning = new AtomicInteger();

    DelayingChatModel(FakeChatModel inner, Duration delay) {
        this.inner = inner;
        this.delay = delay;
    }

    int maxConcurrentCalls() {
        return maxRunning.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        int now = running.incrementAndGet();
        maxRunning.accumulateAndGet(now, Math::max);
        try {
            Thread.sleep(delay);
            return inner.call(prompt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } finally {
            running.decrementAndGet();
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> Flux.just(call(prompt)));
    }
}
