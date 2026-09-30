package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;

/** Per-run cost scopes: reserved before the global day, charged per round trip, never raised by a caller. */
class ScopedCostTests {

    private static final class Lookup {
        final AtomicInteger calls = new AtomicInteger();

        @SuppressWarnings({"UnusedMethod", "EffectivelyPrivate"}) // invoked reflectively through @Tool
        @Tool(description = "looks something up")
        public String lookup(String q) {
            calls.incrementAndGet();
            return "found " + q;
        }
    }

    /** Emits one tool call, then a final answer; counts the round trips it served. */
    private static final class OneToolCallModel implements ChatModel {
        final AtomicInteger trips = new AtomicInteger();

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build(); // tool-capable options, like the OpenAI route's
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            AssistantMessage message = trips.incrementAndGet() == 1
                    ? AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                    List.of(new AssistantMessage.ToolCall("c1", "function", "lookup", "{\"q\":\"x\"}")))
                            .build()
                    : new AssistantMessage("done");
            return ChatResponse.builder()
                    .generations(List.of(new Generation(message)))
                    .metadata(ChatResponseMetadata.builder()
                            .usage(new DefaultUsage(1_000, 100))
                            .build())
                    .build();
        }
    }

    private static DefaultModelRouter router(
            ChatModel chat, CostGuard guard, ScopedCostGuard scoped, boolean requireScope, long maxScopeBudget) {
        RouterProperties base = RouterProperties.defaults();
        var props = new RouterProperties(
                base.routes(),
                base.embedding(),
                base.dailyCapUsdMicros(),
                base.prices(),
                base.openai(),
                null,
                requireScope,
                maxScopeBudget);
        ModelFactory factory = new ModelFactory() {
            @Override
            public ChatModel chatModel(RouterProperties.Route route) {
                return chat;
            }

            @Override
            public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                return new FakeEmbeddingModel(1536);
            }
        };
        return new DefaultModelRouter(
                props, factory, guard, RouterMetrics.NOOP, scoped, io.micrometer.observation.ObservationRegistry.NOOP);
    }

    private static InMemoryCostGuard dayGuard(long cap) {
        return new InMemoryCostGuard(cap, new MutableClock(Instant.parse("2026-09-30T10:00:00Z")));
    }

    private static ChatClient.ChatClientRequestSpec scoped(ChatClient client, String run, long budget) {
        return client.prompt()
                .advisors(a -> a.param(RouterAdvisorParams.COST_SCOPE, run)
                        .param(RouterAdvisorParams.COST_SCOPE_BUDGET_USD_MICROS, budget));
    }

    // ---- the scope survives the recursive tool-calling loop ----

    @Test
    void theScopeSurvivesEveryIterationOfTheToolCallingLoop() {
        var model = new OneToolCallModel();
        var scopeGuard = new InMemoryScopedCostGuard();
        var day = dayGuard(700_000);
        var client = router(model, day, scopeGuard, true, 200_000).chatClient(Tier.TIER0, DataClass.PUBLIC);
        var tools = new Lookup();

        String answer = scoped(client, "run-1", 100_000)
                .tools(tools)
                .user("find x")
                .call()
                .content();

        assertThat(answer).isEqualTo("done");
        assertThat(model.trips).hasValue(2);
        assertThat(tools.calls).hasValue(1);
        // two round trips, each 1000 in * 0.1 + 100 out * 0.5 USD/MTok, priced by usage: 100 + 50 micros each
        assertThat(scopeGuard.spent("run-1")).isEqualTo(Money.usdMicros(2 * (100 + 50)));
        assertThat(day.todayTotal()).isEqualTo(scopeGuard.spent("run-1"));
    }

    @Test
    void aScopeBudgetOfNRefusesTheRoundTripThatWouldExceedItWithoutCallingTheModel() {
        var model = new OneToolCallModel();
        var scopeGuard = new InMemoryScopedCostGuard();
        var day = dayGuard(700_000);
        var client = router(model, day, scopeGuard, true, 200_000).chatClient(Tier.TIER0, DataClass.PUBLIC);

        // a tier0 reservation is about 2002-2010 micros (worst case); the first trip settles at 150. Room for the
        // first reservation, but not for a second one on top of the 150 already spent.
        long budget = 2_100;
        assertThatThrownBy(() -> scoped(client, "run-2", budget)
                        .tools(new Lookup())
                        .user("find x")
                        .call()
                        .content())
                .isInstanceOf(ScopeBudgetExceededException.class);

        assertThat(model.trips).hasValue(1); // the second round trip never reached the model
        assertThat(scopeGuard.spent("run-2").atomicUnits()).isEqualTo(150); // only the first trip, at actual cost
    }

    // ---- reservation order and release ----

    @Test
    void aFirstCallLargerThanTheScopeBudgetNeverReachesTheModelNorTouchesTheDay() {
        var model = new FakeChatModel("x");
        var scopeGuard = new InMemoryScopedCostGuard();
        var day = dayGuard(700_000);
        var client = router(model, day, scopeGuard, false, 200_000).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() -> scoped(client, "run-3", 10).user("hi").call().content())
                .isInstanceOf(ScopeBudgetExceededException.class);

        assertThat(model.callCount()).isZero();
        assertThat(day.todayTotal().atomicUnits()).isZero();
    }

    @Test
    void theScopeIsReleasedWhenTheGlobalDayRefuses() {
        var model = new FakeChatModel("x");
        var scopeGuard = new InMemoryScopedCostGuard();
        var day = dayGuard(1); // the day cannot hold any estimate
        var client = router(model, day, scopeGuard, false, 200_000).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(
                        () -> scoped(client, "run-4", 100_000).user("hi").call().content())
                .isInstanceOf(DailyCapExceededException.class);

        assertThat(model.callCount()).isZero();
        assertThat(scopeGuard.spent("run-4").atomicUnits()).isZero();
    }

    @Test
    void aFailureAfterTheRequestWasSentKeepsBothReservations() {
        var scopeGuard = new InMemoryScopedCostGuard();
        var day = dayGuard(700_000);
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("connection reset");
            }
        };
        var client = router(failing, day, scopeGuard, false, 200_000).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(
                        () -> scoped(client, "run-5", 100_000).user("hi").call().content())
                .hasMessageContaining("connection reset");

        assertThat(scopeGuard.spent("run-5").atomicUnits())
                .isGreaterThan(0)
                .isEqualTo(day.todayTotal().atomicUnits());
    }

    // ---- budget rules ----

    @Test
    void theFirstReservationPinsTheBudgetSoALaterCallCannotRaiseIt() {
        var guard = new InMemoryScopedCostGuard();
        guard.reserve("run-6", Money.usdMicros(60), Money.usdMicros(100));

        assertThatThrownBy(() -> guard.reserve("run-6", Money.usdMicros(60), Money.usdMicros(1_000_000)))
                .isInstanceOf(ScopeBudgetExceededException.class);
        assertThat(guard.spent("run-6")).isEqualTo(Money.usdMicros(60));
    }

    @Test
    void aCallerBudgetAboveTheConfiguredMaximumIsClamped() {
        var model = new FakeChatModel("x");
        var scopeGuard = new InMemoryScopedCostGuard();
        // the maximum cannot hold one worst-case reservation, whatever the caller asks for
        var client = router(model, dayGuard(700_000), scopeGuard, false, 100).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() ->
                        scoped(client, "run-7", 5_000_000).user("hi").call().content())
                .isInstanceOf(ScopeBudgetExceededException.class);
        assertThat(model.callCount()).isZero();
    }

    @Test
    void aCallWithoutABudgetParamGetsTheConfiguredMaximum() {
        var model = new FakeChatModel("x");
        var scopeGuard = new InMemoryScopedCostGuard();
        var client =
                router(model, dayGuard(700_000), scopeGuard, false, 200_000).chatClient(Tier.TIER0, DataClass.PUBLIC);

        client.prompt()
                .advisors(a -> a.param(RouterAdvisorParams.COST_SCOPE, "run-8"))
                .user("hi")
                .call()
                .content();

        assertThat(model.callCount()).isEqualTo(1);
        assertThat(scopeGuard.spent("run-8").atomicUnits()).isPositive();
    }

    @Test
    void invalidBudgetsAndScopeIdsAreRejectedBeforeAnythingIsSent() {
        var model = new FakeChatModel("x");
        var client = router(model, dayGuard(700_000), new InMemoryScopedCostGuard(), false, 200_000)
                .chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() -> scoped(client, "run-9", 0).user("hi").call().content())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        scoped(client, "run:{evil}*", 100).user("hi").call().content())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(model.callCount()).isZero();
    }

    // ---- require-cost-scope ----

    @Test
    void anUnscopedCallFailsBeforeAnythingIsSentWhenAScopeIsRequired() {
        var model = new FakeChatModel("x");
        var day = dayGuard(700_000);
        var client = router(model, day, new InMemoryScopedCostGuard(), true, 200_000)
                .chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() -> client.prompt().user("hi").call().content())
                .isInstanceOf(RequestNotSentException.class)
                .hasMessageContaining(RouterAdvisorParams.COST_SCOPE);

        assertThat(model.callCount()).isZero();
        assertThat(day.todayTotal().atomicUnits()).isZero();
    }

    @Test
    void anUnscopedCallIsAllowedWhenNoScopeIsRequired() {
        var model = new FakeChatModel("x");
        var client = router(model, dayGuard(700_000), new InMemoryScopedCostGuard(), false, 200_000)
                .chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThat(client.prompt().user("hi").call().content()).isEqualTo("x");
    }

    @Test
    void requiringAScopeWithoutAScopedGuardFailsStartup() {
        RouterProperties base = RouterProperties.defaults();
        var props = new RouterProperties(
                base.routes(), base.embedding(), base.dailyCapUsdMicros(), base.prices(), base.openai(), null, true, 1);
        assertThatThrownBy(() -> new DefaultModelRouter(
                        props,
                        new ModelFactory() {
                            @Override
                            public ChatModel chatModel(RouterProperties.Route route) {
                                return new FakeChatModel("x");
                            }

                            @Override
                            public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                                return new FakeEmbeddingModel(1536);
                            }
                        },
                        dayGuard(1),
                        RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScopedCostGuard");
    }

    @Test
    void aScopeGivenToARouterWithoutAScopedGuardIsRefusedNotIgnored() {
        var model = new FakeChatModel("x");
        RouterProperties base = RouterProperties.defaults();
        var router = new DefaultModelRouter(
                base,
                new ModelFactory() {
                    @Override
                    public ChatModel chatModel(RouterProperties.Route route) {
                        return model;
                    }

                    @Override
                    public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                        return new FakeEmbeddingModel(1536);
                    }
                },
                dayGuard(700_000),
                RouterMetrics.NOOP);

        assertThatThrownBy(() -> scoped(router.chatClient(Tier.TIER0, DataClass.PUBLIC), "run-10", 1_000)
                        .user("hi")
                        .call()
                        .content())
                .isInstanceOf(RequestNotSentException.class);
        assertThat(model.callCount()).isZero();
    }

    // ---- concurrency ----

    @Test
    void concurrentReservationsCannotOverspendAScope() throws Exception {
        var guard = new InMemoryScopedCostGuard();
        var granted = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 16; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    guard.reserve("run-11", Money.usdMicros(10), Money.usdMicros(50));
                    granted.incrementAndGet();
                } catch (ScopeBudgetExceededException refused) {
                    // expected for the losers
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(granted).hasValue(5);
        assertThat(guard.spent("run-11")).isEqualTo(Money.usdMicros(50));
    }
}
