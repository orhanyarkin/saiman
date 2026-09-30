package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The daily cap is pinned in code: a configured value above the compiled ceiling fails startup. */
class HardCeilingTests {

    private static final ModelFactory FAKES = new ModelFactory() {
        @Override
        public ChatModel chatModel(RouterProperties.Route route) {
            return new FakeChatModel("x");
        }

        @Override
        public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
            return new FakeEmbeddingModel(1536);
        }
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelRouterAutoConfiguration.class))
            .withPropertyValues("saiman.router.cost-guard=memory");

    @Test
    void theCeilingIsSeventyCentsAndTheDefaultCapEqualsIt() {
        assertThat(DefaultModelRouter.HARD_CEILING_USD_MICROS).isEqualTo(700_000L);
        assertThat(RouterProperties.defaults().dailyCapUsdMicros())
                .isEqualTo(DefaultModelRouter.HARD_CEILING_USD_MICROS);
    }

    @Test
    void aCapAtTheCeilingStarts() {
        var props = RouterProperties.defaults().withDailyCapUsdMicros(700_000);

        assertThat(new DefaultModelRouter(
                        props, FAKES, new InMemoryCostGuard(700_000, Clock.systemUTC()), RouterMetrics.NOOP))
                .isNotNull();
    }

    @Test
    void aCapAboveTheCeilingFailsStartupWithAClearMessage() {
        var props = RouterProperties.defaults().withDailyCapUsdMicros(700_001);

        assertThatThrownBy(() -> new DefaultModelRouter(
                        props, FAKES, new InMemoryCostGuard(700_001, Clock.systemUTC()), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saiman.router.daily-cap-usd-micros")
                .hasMessageContaining("700000")
                .hasMessageContaining("hard ceiling");
    }

    @Test
    void aNegativeCapFailsStartup() {
        var props = RouterProperties.defaults().withDailyCapUsdMicros(-1);

        assertThatThrownBy(() -> new DefaultModelRouter(
                        props, FAKES, new InMemoryCostGuard(0, Clock.systemUTC()), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPropertyAboveTheCeilingFailsTheApplicationContext() {
        runner.withPropertyValues("saiman.router.daily-cap-usd-micros=5000000").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("hard ceiling");
        });
    }

    @Test
    void aPropertyBelowTheCeilingStarts() {
        runner.withPropertyValues("saiman.router.daily-cap-usd-micros=250000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theScopeSettingsBindFromProperties() {
        runner.withPropertyValues(
                        "saiman.router.require-cost-scope=true", "saiman.router.max-scope-budget-usd-micros=150000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var props = context.getBean(RouterProperties.class);
                    assertThat(props.requireCostScope()).isTrue();
                    assertThat(props.maxScopeBudgetUsdMicros()).isEqualTo(150_000L);
                    assertThat(context).hasSingleBean(ScopedCostGuard.class);
                });
    }

    @Test
    void requiringAScopeWithoutAnyScopedGuardFailsTheContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ModelRouterAutoConfiguration.class))
                .withBean(CostGuard.class, () -> new InMemoryCostGuard(1, Clock.systemUTC()))
                .withPropertyValues("saiman.router.require-cost-scope=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("ScopedCostGuard");
                });
    }
}
