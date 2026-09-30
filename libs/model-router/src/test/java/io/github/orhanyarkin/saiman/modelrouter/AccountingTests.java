package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;

/** Streaming pricing and accounting failures after a paid call. */
@ExtendWith(OutputCaptureExtension.class)
class AccountingTests {

    /** Streams the given chunks; an empty array means the chunk carries no usage. */
    private static ChatModel streaming(List<int[]> chunkUsages) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.fromIterable(chunkUsages).map(u -> {
                    var meta = ChatResponseMetadata.builder();
                    if (u.length > 0) {
                        meta.usage(new DefaultUsage(u[0], u[1]));
                    }
                    return ChatResponse.builder()
                            .generations(List.of(new Generation(new AssistantMessage("x"))))
                            .metadata(meta.build())
                            .build();
                });
            }
        };
    }

    private static InMemoryCostGuard guard(long cap) {
        return new InMemoryCostGuard(cap, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
    }

    private static DefaultModelRouter router(ChatModel chat, CostGuard guard, RouterMetrics metrics) {
        return routerWith(chat, new FakeEmbeddingModel(1536), guard, metrics);
    }

    private static DefaultModelRouter routerWith(
            ChatModel chat, EmbeddingModel embedding, CostGuard guard, RouterMetrics metrics) {
        return new DefaultModelRouter(
                RouterProperties.defaults(),
                new ModelFactory() {
                    @Override
                    public ChatModel chatModel(RouterProperties.Route route) {
                        return chat;
                    }

                    @Override
                    public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                        return embedding;
                    }
                },
                guard,
                metrics);
    }

    @Test
    void streamWithUsageOnlyInTheFinalChunkIsPricedOnce() {
        var registry = new SimpleMeterRegistry();
        var guard = guard(10_000_000);
        // final chunk: 1_000_000 in + 500_000 out on tier0 = 100_000 + 250_000
        var chat = streaming(List.of(new int[0], new int[0], new int[] {1_000_000, 500_000}));
        var client =
                router(chat, guard, new MicrometerRouterMetrics(registry)).chatClient(Tier.TIER0, DataClass.PUBLIC);

        List<String> chunks =
                client.prompt().user("hi").stream().content().collectList().block();

        assertThat(chunks).hasSize(3);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(350_000));
        assertThat(registry.get("router.tokens")
                        .tag("direction", "in")
                        .counter()
                        .count())
                .isEqualTo(1_000_000.0);
        assertThat(registry.get("router.calls").tag("outcome", "ok").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("router.calls").tag("outcome", "no_usage").counter())
                .isNull();
    }

    @Test
    void streamWithoutAnyUsageIsCountedLoggedAndNotFailed(CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        var guard = guard(10_000_000);
        var chat = streaming(List.of(new int[0], new int[0]));
        var client =
                router(chat, guard, new MicrometerRouterMetrics(registry)).chatClient(Tier.TIER0, DataClass.PUBLIC);

        List<String> chunks = client.prompt().user("secret question").stream()
                .content()
                .collectList()
                .block();

        assertThat(chunks).hasSize(2);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(0));
        assertThat(registry.get("router.calls")
                        .tag("outcome", "no_usage")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(output).contains("reported no token usage").doesNotContain("secret question");
    }

    /** A guard whose accounting fails after the provider answered. */
    private static final class BrokenRecordGuard implements CostGuard {
        @Override
        public void assertUnderCap() {}

        @Override
        public Money record(Money cost) {
            throw new IllegalStateException("boom with secret-detail");
        }

        @Override
        public Money todayTotal() {
            return Money.usdMicros(0);
        }
    }

    @Test
    void aFailingRecordAfterAPaidChatCallStillReturnsTheAnswerAndLogsTheClassOnly(CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        var chat = new FakeChatModel("the paid answer", 1_000_000, 0);
        var client = router(chat, new BrokenRecordGuard(), new MicrometerRouterMetrics(registry))
                .chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThat(client.prompt().user("hi").call().content()).isEqualTo("the paid answer");

        assertThat(output).contains("java.lang.IllegalStateException").doesNotContain("secret-detail");
        assertThat(registry.get("router.tokens")
                        .tag("direction", "in")
                        .counter()
                        .count())
                .isEqualTo(1_000_000.0);
        assertThat(registry.get("router.calls").tag("outcome", "ok").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void aFailingRecordAfterAPaidEmbeddingCallStillReturnsTheVectors(CapturedOutput output) {
        var embedding = new FakeEmbeddingModel(1536);
        var router = routerWith(new FakeChatModel("x"), embedding, new BrokenRecordGuard(), RouterMetrics.NOOP);

        assertThat(router.embeddingModel(DataClass.PUBLIC).embed(List.of("hello")))
                .hasSize(1);
        assertThat(output).contains("Recording embedding cost failed").doesNotContain("secret-detail");
    }

    @Test
    void aGuardThatCannotBeReadKeepsFailingClosedOnTheNextCall() {
        var chat = new FakeChatModel("ok");
        var flaky = new CostGuard() {
            @Override
            public void assertUnderCap() {
                throw new IllegalStateException("counter unreadable");
            }

            @Override
            public Money record(Money cost) {
                throw new IllegalStateException("counter unreadable");
            }

            @Override
            public Money todayTotal() {
                throw new IllegalStateException("counter unreadable");
            }
        };
        var client = router(chat, flaky, RouterMetrics.NOOP).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() -> client.prompt().user("hi").call().content()).isInstanceOf(IllegalStateException.class);
        assertThat(chat.callCount()).isZero();
    }
}
