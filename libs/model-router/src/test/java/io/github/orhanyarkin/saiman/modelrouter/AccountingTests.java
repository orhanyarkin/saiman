package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;

/**
 * Reservation accounting: what is charged when usage is streamed, missing, unusable, or the call
 * fails after the provider was reached.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountingTests {

    /** Reservation for a two-character prompt on tier0: (1 * 0.1 + 2000 * 0.5) USD/MTok, ceil, x2 retries. */
    private static final long TIER0_ESTIMATE = 2002;

    private record Chunk(int in, int out, @Nullable String model, boolean hasUsage) {
        static Chunk noUsage() {
            return new Chunk(0, 0, null, false);
        }

        static Chunk usage(int in, int out) {
            return new Chunk(in, out, null, true);
        }
    }

    /** Streams the given chunks, then optionally fails. */
    private static ChatModel streaming(List<Chunk> chunks, @Nullable RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                Flux<ChatResponse> flux = Flux.fromIterable(chunks).map(AccountingTests::response);
                return failure == null ? flux : flux.concatWith(Flux.error(failure));
            }
        };
    }

    private static ChatResponse response(Chunk c) {
        var meta = ChatResponseMetadata.builder();
        if (c.hasUsage()) {
            meta.usage(new DefaultUsage(c.in(), c.out()));
        }
        if (c.model() != null) {
            meta.model(c.model());
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("x"))))
                .metadata(meta.build())
                .build();
    }

    private static ChatModel calling(Chunk chunk) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response(chunk);
            }
        };
    }

    private static ChatModel failing(RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw failure;
            }
        };
    }

    /** In-memory guard that records estimates and can break settle. */
    private static final class RecordingGuard implements CostGuard {
        final InMemoryCostGuard delegate;
        final List<Long> estimates = new ArrayList<>();
        boolean failSettle;

        RecordingGuard(long cap) {
            delegate = new InMemoryCostGuard(cap, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
        }

        @Override
        public Reservation reserve(Money estimate) {
            estimates.add(estimate.atomicUnits());
            return delegate.reserve(estimate);
        }

        @Override
        public void settle(Reservation reservation, Money actual) {
            if (failSettle) {
                throw new IllegalStateException("boom with secret-detail");
            }
            delegate.settle(reservation, actual);
        }

        @Override
        public Money todayTotal() {
            return delegate.todayTotal();
        }

        long total() {
            return delegate.todayTotal().atomicUnits();
        }
    }

    private static DefaultModelRouter routerWith(
            RouterProperties properties, ChatModel chat, EmbeddingModel embedding, CostGuard guard, RouterMetrics m) {
        return new DefaultModelRouter(
                properties,
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
                m);
    }

    private static DefaultModelRouter router(ChatModel chat, CostGuard guard, RouterMetrics metrics) {
        return routerWith(RouterProperties.defaults(), chat, new FakeEmbeddingModel(1536), guard, metrics);
    }

    private static org.springframework.ai.chat.client.ChatClient tier0(
            ChatModel chat, CostGuard guard, RouterMetrics metrics) {
        return router(chat, guard, metrics).chatClient(Tier.TIER0, DataClass.PUBLIC);
    }

    // ---- streams ----

    @Test
    void streamWithUsageOnlyInTheFinalChunkIsSettledOnceAtTheActualCost() {
        var registry = new SimpleMeterRegistry();
        var guard = new RecordingGuard(10_000_000);
        var chat = streaming(List.of(Chunk.noUsage(), Chunk.noUsage(), Chunk.usage(1_000_000, 500_000)), null);

        List<String> chunks = tier0(chat, guard, new MicrometerRouterMetrics(registry)).prompt().user("hi").stream()
                .content()
                .collectList()
                .block();

        assertThat(chunks).hasSize(3);
        assertThat(guard.estimates).containsExactly(TIER0_ESTIMATE);
        assertThat(guard.total()).isEqualTo(350_000); // 100_000 in + 250_000 out, the estimate is gone
        assertThat(registry.get("router.calls").tag("outcome", "ok").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("router.calls").tag("outcome", "no_usage").counter())
                .isNull();
    }

    @Test
    void streamWithoutAnyUsageKeepsTheEstimateAndIsCountedAndLoggedNotFailed(CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        var guard = new RecordingGuard(10_000_000);
        var chat = streaming(List.of(Chunk.noUsage(), Chunk.noUsage()), null);

        List<String> chunks =
                tier0(chat, guard, new MicrometerRouterMetrics(registry)).prompt().user("secret question").stream()
                        .content()
                        .collectList()
                        .block();

        assertThat(chunks).hasSize(2);
        assertThat(guard.total()).isEqualTo(guard.estimates.get(0));
        assertThat(registry.get("router.calls")
                        .tag("outcome", "no_usage")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(output).contains("no usable token usage").doesNotContain("secret question");
    }

    @Test
    void streamCancelledAfterTheFirstChunkKeepsTheEstimate() {
        var registry = new SimpleMeterRegistry();
        var guard = new RecordingGuard(10_000_000);
        var chat = streaming(List.of(Chunk.noUsage(), Chunk.noUsage(), Chunk.usage(1_000_000, 500_000)), null);

        List<String> first = tier0(chat, guard, new MicrometerRouterMetrics(registry)).prompt().user("hi").stream()
                .content()
                .take(1)
                .collectList()
                .block();

        assertThat(first).hasSize(1);
        assertThat(guard.total()).isEqualTo(TIER0_ESTIMATE);
        assertThat(registry.get("router.calls")
                        .tag("outcome", "cancelled")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void streamThatErrorsMidwayKeepsTheEstimateAndPropagatesTheError() {
        var guard = new RecordingGuard(10_000_000);
        var chat = streaming(List.of(Chunk.noUsage()), new IllegalStateException("upstream broke"));

        assertThatThrownBy(() -> tier0(chat, guard, RouterMetrics.NOOP).prompt().user("hi").stream()
                        .content()
                        .collectList()
                        .block())
                .hasMessageContaining("upstream broke");

        assertThat(guard.total()).isEqualTo(TIER0_ESTIMATE);
    }

    @Test
    void streamThatErrorsAfterUsageArrivedIsSettledAtTheActualCost() {
        var guard = new RecordingGuard(10_000_000);
        var chat = streaming(List.of(Chunk.usage(1_000_000, 0)), new IllegalStateException("late failure"));

        assertThatThrownBy(() -> tier0(chat, guard, RouterMetrics.NOOP).prompt().user("hi").stream()
                        .content()
                        .collectList()
                        .block())
                .hasMessageContaining("late failure");

        assertThat(guard.total()).isEqualTo(100_000);
    }

    @Test
    void streamWithNegativeUsageKeepsTheEstimate() {
        var guard = new RecordingGuard(10_000_000);
        var chat = streaming(List.of(Chunk.usage(-5, -7)), null);

        tier0(chat, guard, RouterMetrics.NOOP).prompt().user("hi").stream()
                .content()
                .collectList()
                .block();

        assertThat(guard.total()).isEqualTo(TIER0_ESTIMATE);
    }

    // ---- non-streaming calls ----

    @Test
    void responseWithoutUsageKeepsTheEstimate() {
        var guard = new RecordingGuard(10_000_000);

        tier0(calling(Chunk.noUsage()), guard, RouterMetrics.NOOP)
                .prompt()
                .user("hi")
                .call()
                .content();

        assertThat(guard.total()).isEqualTo(TIER0_ESTIMATE);
    }

    @Test
    void negativeUsageKeepsTheEstimate() {
        var guard = new RecordingGuard(10_000_000);

        tier0(calling(Chunk.usage(-1, 5)), guard, RouterMetrics.NOOP)
                .prompt()
                .user("hi")
                .call()
                .content();

        assertThat(guard.total()).isEqualTo(TIER0_ESTIMATE);
    }

    @Test
    void absurdlyLargeUsageIsSettledAtTheTruthAndEndsTheDay() {
        var guard = new RecordingGuard(1_000_000);
        var client = tier0(calling(Chunk.usage(Integer.MAX_VALUE, 0)), guard, RouterMetrics.NOOP);

        client.prompt().user("hi").call().content();

        assertThat(guard.total()).isGreaterThan(1_000_000);
        assertThatThrownBy(() -> client.prompt().user("again").call().content())
                .isInstanceOf(DailyCapExceededException.class);
    }

    @Test
    void usageThatOverflowsThePriceArithmeticKeepsTheEstimate() {
        RouterProperties base = RouterProperties.defaults();
        var prices = new java.util.HashMap<>(base.prices());
        prices.put("gpt-5-nano", new RouterProperties.Price(10_000_000_000L, 0)); // 1e10 USD micros per MTok
        var props = new RouterProperties(
                base.routes(), base.embedding(), 700_000L, Map.copyOf(prices), base.openai(), null);
        var guard = new RecordingGuard(700_000L);
        var chat = calling(Chunk.usage(Integer.MAX_VALUE, 0)); // 2.1e9 tokens * 1e10 = 2e19, beyond a long

        routerWith(props, chat, new FakeEmbeddingModel(1536), guard, RouterMetrics.NOOP)
                .chatClient(Tier.TIER0, DataClass.PUBLIC)
                .prompt()
                .user("hi")
                .call()
                .content();

        assertThat(guard.total()).isEqualTo(guard.estimates.get(0));
    }

    @Test
    void callThatFailsAfterBeingSentKeepsTheReservation() {
        var guard = new RecordingGuard(10_000_000);
        var client = tier0(failing(new IllegalStateException("connection reset")), guard, RouterMetrics.NOOP);

        assertThatThrownBy(() -> client.prompt().user("hi").call().content()).hasMessageContaining("connection reset");

        assertThat(guard.total()).isEqualTo(TIER0_ESTIMATE);
    }

    @Test
    void requestThatNeverLeftGivesTheReservationBack() {
        var guard = new RecordingGuard(10_000_000);
        var client = tier0(failing(new RequestNotSentException("no key")), guard, RouterMetrics.NOOP);

        assertThatThrownBy(() -> client.prompt().user("hi").call().content())
                .isInstanceOf(RequestNotSentException.class);

        assertThat(guard.total()).isZero();
    }

    // ---- accounting failures ----

    @Test
    void aFailingSettleAfterAPaidChatCallStillReturnsTheAnswerAndLogsTheClassOnly(CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        var guard = new RecordingGuard(10_000_000);
        guard.failSettle = true;
        var chat = new FakeChatModel("the paid answer", 1_000_000, 0);

        var answer = tier0(chat, guard, new MicrometerRouterMetrics(registry))
                .prompt()
                .user("hi")
                .call()
                .content();

        assertThat(answer).isEqualTo("the paid answer");
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
    void aFailingSettleAfterAPaidEmbeddingCallStillReturnsTheVectors(CapturedOutput output) {
        var guard = new RecordingGuard(10_000_000);
        guard.failSettle = true;
        var router = routerWith(
                RouterProperties.defaults(),
                new FakeChatModel("x"),
                new FakeEmbeddingModel(1536),
                guard,
                RouterMetrics.NOOP);

        assertThat(router.embeddingModel(DataClass.PUBLIC).embed(List.of("hello")))
                .hasSize(1);
        assertThat(output).contains("Accounting an embedding call failed").doesNotContain("secret-detail");
    }

    @Test
    void aGuardThatCannotBeReadFailsClosedBeforeTheModelIsReached() {
        var chat = new FakeChatModel("ok");
        var broken = new CostGuard() {
            @Override
            public Reservation reserve(Money estimate) {
                throw new IllegalStateException("counter unreadable");
            }

            @Override
            public void settle(Reservation reservation, Money actual) {}

            @Override
            public Money todayTotal() {
                throw new IllegalStateException("counter unreadable");
            }
        };
        var client = tier0(chat, broken, RouterMetrics.NOOP);

        assertThatThrownBy(() -> client.prompt().user("hi").call().content()).isInstanceOf(IllegalStateException.class);
        assertThat(chat.callCount()).isZero();
    }

    // ---- per-request overrides ----

    @Test
    void aModelOverrideInTheRequestRaisesTheEstimateToThatModelsPrice() {
        var guard = new RecordingGuard(100_000_000);
        var client = tier0(new FakeChatModel("ok", 10, 10), guard, RouterMetrics.NOOP);

        client.prompt()
                .user("hi")
                .options(ChatOptions.builder().model("gpt-5").maxTokens(2000))
                .call()
                .content();

        // (1 * 0.75 + 2000 * 3.75) USD/MTok = 7_500_750_000 / 1e6 -> 7501, x2 retries
        assertThat(guard.estimates).containsExactly(15_002L);
    }

    @Test
    void aReportedModelOutsideTheTablePaysTheHighestConfiguredPriceAndLogsAWarning(CapturedOutput output) {
        var guard = new RecordingGuard(100_000_000);
        var chat = calling(new Chunk(1_000_000, 0, "some-other-model", true));

        tier0(chat, guard, RouterMetrics.NOOP).prompt().user("hi").call().content();

        assertThat(guard.total()).isEqualTo(1_000_000); // highest input price is 1_000_000 per MTok
        assertThat(output).contains("Provider reported model some-other-model");
    }

    @Test
    void aReportedModelInTheTablePaysItsOwnPriceButNeverLessThanTheRoutePrice() {
        var guard = new RecordingGuard(100_000_000);
        var chat = calling(new Chunk(1_000_000, 0, "gpt-5", true)); // 750_000 per MTok in, dearer than nano

        tier0(chat, guard, RouterMetrics.NOOP).prompt().user("hi").call().content();

        assertThat(guard.total()).isEqualTo(750_000);

        var cheaperGuard = new RecordingGuard(100_000_000);
        var tier2Chat = calling(new Chunk(1_000_000, 0, "gpt-5-nano", true));
        router(tier2Chat, cheaperGuard, RouterMetrics.NOOP)
                .chatClient(Tier.TIER2, DataClass.PUBLIC)
                .prompt()
                .user("hi")
                .call()
                .content();
        assertThat(cheaperGuard.total()).isEqualTo(1_000_000); // tier2 route price (1.0 USD/MTok) is the floor
    }

    @Test
    void aDatedSnapshotOfTheRoutedModelIsNotAnOverride(CapturedOutput output) {
        var guard = new RecordingGuard(100_000_000);
        var chat = calling(new Chunk(1_000_000, 0, "gpt-5-nano-2025-08-07", true));

        tier0(chat, guard, RouterMetrics.NOOP).prompt().user("hi").call().content();

        assertThat(guard.total()).isEqualTo(100_000);
        assertThat(output).doesNotContain("Provider reported model");
    }

    @Test
    void streamUsageCannotBeSwitchedOffByTheCaller() {
        var callerOptions = org.springframework.ai.openai.OpenAiChatOptions.builder()
                .streamOptions(org.springframework.ai.openai.OpenAiChatOptions.StreamOptions.builder()
                        .includeUsage(false)
                        .build())
                .build();

        Prompt forced = OpenAiModelFactory.withStreamUsage(new Prompt("hi", callerOptions));

        var options = (org.springframework.ai.openai.OpenAiChatOptions) forced.getOptions();
        assertThat(options).isNotNull();
        assertThat(options.getStreamOptions().includeUsage()).isTrue();
    }
}
