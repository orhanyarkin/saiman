package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.mock.env.MockEnvironment;

class DefaultModelRouterTests {

    private static final long TIER0_INPUT_MTOK_PRICE = 100_000; // gpt-5-nano default, USD micros per MTok

    /** Hands out the same counting fakes for every route. */
    private static final class FakeFactory implements ModelFactory {
        final FakeChatModel chat;
        final FakeEmbeddingModel embedding = new FakeEmbeddingModel(1536);

        FakeFactory(int promptTokens, int completionTokens) {
            this.chat = new FakeChatModel("ok", promptTokens, completionTokens);
        }

        @Override
        public ChatModel chatModel(RouterProperties.Route route) {
            return chat;
        }

        @Override
        public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
            return embedding;
        }
    }

    private static RouterProperties defaults() {
        return RouterPropertiesBinder.bind(new MockEnvironment());
    }

    private static InMemoryCostGuard guard(long cap) {
        return new InMemoryCostGuard(cap, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
    }

    private static RouterProperties withRoute(RouterProperties p, Tier tier, RouterProperties.Route route) {
        var routes = new EnumMap<>(p.routes());
        routes.put(tier, route);
        return new RouterProperties(routes, p.embedding(), p.dailyCapUsdMicros(), p.prices(), p.openai(), null);
    }

    @Test
    void defaultsCarryAllTiersPricesTheCapAndClientLimits() {
        RouterProperties p = defaults();

        assertThat(p.routes()).containsKeys(Tier.values());
        assertThat(p.routes().get(Tier.TIER1_PREMIUM).provider()).isEqualTo("openai");
        assertThat(p.routes().get(Tier.TIER0).allowedDataClasses())
                .containsExactlyInAnyOrder(DataClass.PUBLIC, DataClass.INTERNAL);
        assertThat(p.embedding().model()).isEqualTo("text-embedding-3-small");
        assertThat(p.embedding().dimensions()).isEqualTo(1536);
        assertThat(p.dailyCapUsdMicros()).isEqualTo(700_000L);
        assertThat(p.prices().get("text-embedding-3-small")).isEqualTo(new RouterProperties.Price(20_000, 0));
        assertThat(p.prices().get(p.routes().get(Tier.TIER0).model()).inputUsdMicrosPerMtok())
                .isEqualTo(TIER0_INPUT_MTOK_PRICE);
        assertThat(p.openai().maxRetries()).isEqualTo(1);
        assertThat(p.openai().timeout()).hasSeconds(30);
    }

    @Test
    void everyRouteHasMaxCompletionTokens() {
        RouterProperties p = defaults();
        for (Tier tier : Tier.values()) {
            assertThat(p.routes().get(tier).maxCompletionTokens())
                    .as("%s", tier)
                    .isPositive();
        }
        assertThat(p.routes().get(Tier.TIER0).maxCompletionTokens()).isEqualTo(2000);

        for (Tier tier : Tier.values()) {
            var route = p.routes().get(tier);
            var broken = withRoute(
                    p,
                    tier,
                    new RouterProperties.Route(
                            route.provider(), route.model(), 0, route.allowedDataClasses(), route.region()));
            assertThatThrownBy(() ->
                            new DefaultModelRouter(broken, new FakeFactory(1, 1), guard(1_000), RouterMetrics.NOOP))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-completion-tokens");
        }
    }

    @Test
    void aMissingMaxCompletionTokensInConfigurationFailsStartup() {
        var env = new MockEnvironment().withProperty("saiman.router.routes.tier1.max-completion-tokens", "0");

        assertThatThrownBy(() -> new DefaultModelRouter(
                        RouterPropertiesBinder.bind(env), new FakeFactory(1, 1), guard(1_000), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tier1.max-completion-tokens");
    }

    @Test
    void applicationPropertiesOverrideTheClasspathDefaults() {
        var env = new MockEnvironment()
                .withProperty("saiman.router.daily-cap-usd-micros", "123")
                .withProperty("saiman.router.routes.tier1.model", "custom-model")
                .withProperty("saiman.router.prices.custom-model.input-usd-micros-per-mtok", "5")
                .withProperty("saiman.router.prices.custom-model.output-usd-micros-per-mtok", "6");

        RouterProperties p = RouterPropertiesBinder.bind(env);

        assertThat(p.dailyCapUsdMicros()).isEqualTo(123);
        assertThat(p.routes().get(Tier.TIER1).model()).isEqualTo("custom-model");
        assertThat(p.routes().get(Tier.TIER1).provider()).isEqualTo("openai"); // untouched default keys survive
        assertThat(p.routes().get(Tier.TIER1).maxCompletionTokens()).isEqualTo(3000);
        assertThat(p.routes()).containsKey(Tier.TIER0);
        assertThat(p.prices().get("custom-model")).isEqualTo(new RouterProperties.Price(5, 6));
        assertThat(p.prices()).containsKey("gpt-5-nano");
    }

    @Test
    void refusedDataClassNeverReachesTheModel() {
        var factory = new FakeFactory(10, 10);
        var router = new DefaultModelRouter(defaults(), factory, guard(1_000_000), RouterMetrics.NOOP);

        for (Tier tier : Tier.values()) {
            assertThatThrownBy(() -> router.chatClient(tier, DataClass.SENSITIVE))
                    .isInstanceOf(DataClassViolationException.class)
                    .hasMessageContaining("SENSITIVE")
                    .hasMessageContaining(tier.name());
        }
        assertThatThrownBy(() -> router.embeddingModel(DataClass.SENSITIVE))
                .isInstanceOf(DataClassViolationException.class)
                .hasMessageContaining("SENSITIVE");

        assertThat(factory.chat.callCount()).isZero();
        assertThat(factory.embedding.callCount()).isZero();
    }

    @Test
    void routeThatDoesNotAllowInternalRefusesItButStillServesPublic() {
        RouterProperties base = defaults();
        var tier2 = base.routes().get(Tier.TIER2);
        var props = withRoute(
                base,
                Tier.TIER2,
                new RouterProperties.Route(
                        tier2.provider(), tier2.model(), tier2.maxCompletionTokens(), Set.of(DataClass.PUBLIC), "us"));
        var factory = new FakeFactory(10, 10);
        var router = new DefaultModelRouter(props, factory, guard(1_000_000), RouterMetrics.NOOP);

        assertThatThrownBy(() -> router.chatClient(Tier.TIER2, DataClass.INTERNAL))
                .isInstanceOf(DataClassViolationException.class);
        assertThat(factory.chat.callCount()).isZero();

        assertThat(router.chatClient(Tier.TIER2, DataClass.PUBLIC)
                        .prompt()
                        .user("hi")
                        .call()
                        .content())
                .isEqualTo("ok");
        assertThat(router.chatClient(Tier.TIER0, DataClass.INTERNAL)
                        .prompt()
                        .user("hi")
                        .call()
                        .content())
                .isEqualTo("ok");
        assertThat(factory.chat.callCount()).isEqualTo(2);
    }

    @Test
    void emptyAllowedSetRefusesEveryClass() {
        RouterProperties base = defaults();
        var tier0 = base.routes().get(Tier.TIER0);
        var props = withRoute(
                base,
                Tier.TIER0,
                new RouterProperties.Route(
                        tier0.provider(), tier0.model(), tier0.maxCompletionTokens(), Set.of(), "us"));
        var factory = new FakeFactory(1, 1);
        var router = new DefaultModelRouter(props, factory, guard(1_000_000), RouterMetrics.NOOP);

        for (DataClass dataClass : DataClass.values()) {
            assertThatThrownBy(() -> router.chatClient(Tier.TIER0, dataClass))
                    .isInstanceOf(DataClassViolationException.class);
        }
        assertThat(factory.chat.callCount()).isZero();
    }

    @Test
    void aRouteThatAllowsSensitiveFailsStartup() {
        RouterProperties base = defaults();
        var tier1 = base.routes().get(Tier.TIER1);
        var props = withRoute(
                base,
                Tier.TIER1,
                new RouterProperties.Route(
                        tier1.provider(),
                        tier1.model(),
                        tier1.maxCompletionTokens(),
                        Set.of(DataClass.PUBLIC, DataClass.SENSITIVE),
                        "us"));

        assertThatThrownBy(() -> new DefaultModelRouter(props, new FakeFactory(1, 1), guard(1), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENSITIVE");

        var embedding = base.embedding();
        var badEmbedding = new RouterProperties(
                base.routes(),
                new RouterProperties.Embedding(
                        embedding.provider(),
                        embedding.model(),
                        embedding.dimensions(),
                        Set.of(DataClass.SENSITIVE),
                        "us"),
                base.dailyCapUsdMicros(),
                base.prices(),
                base.openai(),
                null);
        assertThatThrownBy(
                        () -> new DefaultModelRouter(badEmbedding, new FakeFactory(1, 1), guard(1), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SENSITIVE");
    }

    @Test
    void dailyCapAllowsCallsWhoseReservationFitsAndRefusesOnceItWouldNotWithoutCallingTheModel() {
        // 1_000_000 prompt tokens on tier0 cost exactly 100_000 micro-dollars per call; the reservation
        // for a tiny prompt is 2_002 (worst case: 2000 completion tokens, one retry)
        var factory = new FakeFactory(1_000_000, 0);
        var guard = guard(250_000);
        var router =
                new DefaultModelRouter(defaults().withDailyCapUsdMicros(250_000), factory, guard, RouterMetrics.NOOP);
        var client = router.chatClient(Tier.TIER0, DataClass.PUBLIC);

        client.prompt().user("1").call().content(); // settled at 100_000
        client.prompt().user("2").call().content(); // 200_000
        client.prompt().user("3").call().content(); // reserved at 202_002 <= cap, settled at 300_000
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(300_000));

        assertThatThrownBy(() -> client.prompt().user("4").call().content())
                .isInstanceOf(DailyCapExceededException.class);
        assertThat(factory.chat.callCount()).isEqualTo(3);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(300_000));
    }

    @Test
    void embeddingCallsAreReservedAndPricedToo() {
        var factory = new FakeFactory(1, 1);
        var guard = guard(12);
        var router = new DefaultModelRouter(defaults().withDailyCapUsdMicros(12), factory, guard, RouterMetrics.NOOP);
        EmbeddingModel embeddings = router.embeddingModel(DataClass.PUBLIC);

        assertThat(embeddings.dimensions()).isEqualTo(1536);
        // 400 chars: reserved 8 (200 tokens, one retry), actual 100 tokens = 2 micro-dollars
        assertThat(embeddings.embed(List.of("x".repeat(400))).get(0)).hasSize(1536);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(2));

        for (int i = 0; i < 2; i++) {
            embeddings.embed(List.of("x".repeat(400)));
        }
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(6));
        int callsSoFar = factory.embedding.callCount();

        assertThatThrownBy(() -> embeddings.embed(List.of("x".repeat(400))))
                .isInstanceOf(DailyCapExceededException.class);
        assertThat(factory.embedding.callCount()).isEqualTo(callsSoFar);
    }

    @Test
    void metricsCountTokensCostAndOutcomesWithoutPromptTextInTags() {
        var registry = new SimpleMeterRegistry();
        var factory = new FakeFactory(1_000_000, 500_000);
        var router = new DefaultModelRouter(
                defaults().withDailyCapUsdMicros(200_000),
                factory,
                guard(200_000),
                new MicrometerRouterMetrics(registry));
        var client = router.chatClient(Tier.TIER0, DataClass.PUBLIC);

        client.prompt().user("very secret prompt text").call().content(); // 100_000 + 250_000
        assertThatThrownBy(() -> client.prompt().user("again").call().content())
                .isInstanceOf(DailyCapExceededException.class);

        assertThat(registry.get("router.tokens")
                        .tag("tier", "tier0")
                        .tag("direction", "in")
                        .counter()
                        .count())
                .isEqualTo(1_000_000.0);
        assertThat(registry.get("router.tokens")
                        .tag("tier", "tier0")
                        .tag("direction", "out")
                        .counter()
                        .count())
                .isEqualTo(500_000.0);
        assertThat(registry.get("router.cost.usd_micros")
                        .tag("tier", "tier0")
                        .counter()
                        .count())
                .isEqualTo(350_000.0);
        assertThat(registry.get("router.calls")
                        .tag("tier", "tier0")
                        .tag("outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("router.calls")
                        .tag("tier", "tier0")
                        .tag("outcome", "cap")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        registry.getMeters()
                .forEach(meter -> meter.getId()
                        .getTags()
                        .forEach(tag -> assertThat(tag.getValue())
                                .doesNotContain("secret")
                                .doesNotContain("again")));
    }

    @Test
    void startupFailsClearlyWhenARouteHasNoPrice() {
        RouterProperties base = defaults();
        var props = new RouterProperties(
                base.routes(), base.embedding(), base.dailyCapUsdMicros(), Map.of(), base.openai(), null);

        assertThatThrownBy(() -> new DefaultModelRouter(props, new FakeFactory(1, 1), guard(1), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prices");
    }
}
