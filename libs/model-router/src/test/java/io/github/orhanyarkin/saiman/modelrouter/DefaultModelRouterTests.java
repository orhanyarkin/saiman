package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
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

    private static RouterProperties withCap(RouterProperties p, long cap) {
        return new RouterProperties(p.routes(), p.embedding(), cap, p.prices(), p.openai());
    }

    private static InMemoryCostGuard guard(long cap) {
        return new InMemoryCostGuard(cap, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
    }

    @Test
    void defaultsCarryAllTiersPricesAndTheCap() {
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
        var routes = new java.util.EnumMap<>(base.routes());
        var tier2 = routes.get(Tier.TIER2);
        routes.put(
                Tier.TIER2,
                new RouterProperties.Route(tier2.provider(), tier2.model(), Set.of(DataClass.PUBLIC), tier2.region()));
        var props =
                new RouterProperties(routes, base.embedding(), base.dailyCapUsdMicros(), base.prices(), base.openai());
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
    void dailyCapAllowsCallsUnderItAndRefusesOnceReachedWithoutCallingTheModel() {
        // 1_000_000 prompt tokens on tier0 cost exactly 100_000 micro-dollars per call
        var factory = new FakeFactory(1_000_000, 0);
        var guard = guard(250_000);
        var router = new DefaultModelRouter(withCap(defaults(), 250_000), factory, guard, RouterMetrics.NOOP);
        var client = router.chatClient(Tier.TIER0, DataClass.PUBLIC);

        client.prompt().user("1").call().content(); // total 100_000
        client.prompt().user("2").call().content(); // total 200_000 < cap
        client.prompt().user("3").call().content(); // total 300_000 >= cap
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(300_000));

        assertThatThrownBy(() -> client.prompt().user("4").call().content())
                .isInstanceOf(DailyCapExceededException.class);
        assertThat(factory.chat.callCount()).isEqualTo(3);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(300_000));
    }

    @Test
    void embeddingCallsAreCappedAndPricedToo() {
        var factory = new FakeFactory(1, 1);
        var guard = guard(10);
        var router = new DefaultModelRouter(withCap(defaults(), 10), factory, guard, RouterMetrics.NOOP);
        EmbeddingModel embeddings = router.embeddingModel(DataClass.PUBLIC);

        assertThat(embeddings.dimensions()).isEqualTo(1536);
        // 400 chars -> 100 tokens -> 100 * 20_000 / 1_000_000 = 2 micro-dollars
        assertThat(embeddings.embed(List.of("x".repeat(400))).get(0)).hasSize(1536);
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(2));

        for (int i = 0; i < 4; i++) {
            embeddings.embed(List.of("x".repeat(400)));
        }
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(10));
        int callsAtCap = factory.embedding.callCount();

        assertThatThrownBy(() -> embeddings.embed(List.of("more"))).isInstanceOf(DailyCapExceededException.class);
        assertThat(factory.embedding.callCount()).isEqualTo(callsAtCap);
    }

    @Test
    void metricsCountTokensCostAndOutcomesWithoutPromptTextInTags() {
        var registry = new SimpleMeterRegistry();
        var factory = new FakeFactory(1_000_000, 500_000);
        var router = new DefaultModelRouter(
                withCap(defaults(), 200_000), factory, guard(200_000), new MicrometerRouterMetrics(registry));
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
                base.routes(), base.embedding(), base.dailyCapUsdMicros(), Map.of(), base.openai());

        assertThatThrownBy(() -> new DefaultModelRouter(props, new FakeFactory(1, 1), guard(1), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prices");
    }
}
