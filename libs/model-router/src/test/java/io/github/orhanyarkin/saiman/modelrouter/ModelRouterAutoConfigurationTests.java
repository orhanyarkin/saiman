package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class ModelRouterAutoConfigurationTests {

    private static final String PLANTED_KEY = "sk-planted-1234567890-SHOULD-NEVER-APPEAR";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelRouterAutoConfiguration.class))
            .withPropertyValues("saiman.router.cost-guard=memory");

    private final ApplicationContextRunner noExplicitGuard =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ModelRouterAutoConfiguration.class));

    @Test
    void withoutValkeyAndWithoutTheExplicitMemoryPropertyStartupFailsWithAClearMessage() {
        noExplicitGuard.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("saiman.router.cost-guard=memory")
                    .hasMessageContaining("spring.data.redis");
        });
    }

    @Test
    void withAStringRedisTemplateNoExplicitPropertyIsNeeded() {
        var template = new StringRedisTemplate(
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory());
        noExplicitGuard.withBean(StringRedisTemplate.class, () -> template).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CostGuard.class)).isInstanceOf(ValkeyCostGuard.class);
        });
    }

    @Test
    void riskyEnvironmentVariablesProduceWarningsWithoutValues(CapturedOutput output) {
        runner.withPropertyValues(
                        "OPENAI_LOG=debug",
                        "OPENAI_BASE_URL=http://evil.example:1",
                        "AZURE_OPENAI_BASE_URL=http://evil2.example:1")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output)
                .contains("OPENAI_LOG is set")
                .contains("OPENAI_BASE_URL is set but ignored")
                .contains("AZURE_OPENAI_BASE_URL is set but ignored")
                .doesNotContain("evil.example")
                .doesNotContain("evil2.example");
    }

    @Test
    void aCleanEnvironmentProducesNoWarning(CapturedOutput output) {
        runner.run(context -> assertThat(context).hasNotFailed());

        assertThat(output).doesNotContain("OPENAI_LOG is set").doesNotContain("is set but ignored");
    }

    @Test
    void routerBeanExistsWithoutAnyKeyAndFallsBackToAnInMemoryGuard(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ModelRouter.class);
            assertThat(context.getBean(CostGuard.class)).isInstanceOf(InMemoryCostGuard.class);
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
            assertThat(context.getBean(RouterMetrics.class)).isSameAs(RouterMetrics.NOOP);
        });
        assertThat(output).contains("counted in memory");
    }

    @Test
    void firstCallFailsClosedWithoutKeyAndTheMessageHasNoValue() {
        runner.run(context -> {
            ModelRouter router = context.getBean(ModelRouter.class);
            ChatClient client = router.chatClient(Tier.TIER0, DataClass.PUBLIC);
            EmbeddingModel embeddings = router.embeddingModel(DataClass.PUBLIC);

            assertThatThrownBy(() -> client.prompt().user("hello").call().content())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OpenAI API key is not configured")
                    .hasMessageNotContaining("hello");
            assertThatThrownBy(() -> embeddings.embed(java.util.List.of("hello")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OpenAI API key is not configured");
        });
    }

    @Test
    void refusedDataClassFailsBeforeTheKeyIsEvenLookedAt() {
        runner.run(context -> assertThatThrownBy(
                        () -> context.getBean(ModelRouter.class).chatClient(Tier.TIER1, DataClass.SENSITIVE))
                .isInstanceOf(DataClassViolationException.class));
    }

    @Test
    void plantedKeyNeverAppearsInToStringMessagesOrLogs(CapturedOutput output) {
        runner.withPropertyValues("saiman.router.openai.api-key=" + PLANTED_KEY).run(context -> {
            RouterProperties props = context.getBean(RouterProperties.class);
            assertThat(props.openai().hasApiKey()).isTrue();
            assertThat(props.toString()).doesNotContain(PLANTED_KEY).contains("REDACTED");
            assertThat(props.openai().toString()).doesNotContain(PLANTED_KEY);
            assertThat(context.getBean(ModelRouter.class).toString()).doesNotContain(PLANTED_KEY);
        });
        assertThat(output).doesNotContain(PLANTED_KEY);
    }

    @Test
    void configTreeStyleUnderscorePropertyIsPickedUp() {
        var env = new MockEnvironment().withProperty("openai_api_key", PLANTED_KEY);

        RouterProperties props = RouterPropertiesBinder.bind(env);

        assertThat(props.openai().hasApiKey()).isTrue();
        assertThat(props.toString()).doesNotContain(PLANTED_KEY);
    }

    @Test
    void blankKeyCountsAsMissing() {
        runner.withPropertyValues("saiman.router.openai.api-key=   ").run(context -> {
            assertThat(context.getBean(RouterProperties.class).openai().hasApiKey())
                    .isFalse();
            assertThatThrownBy(() -> context.getBean(ModelRouter.class)
                            .embeddingModel(DataClass.PUBLIC)
                            .embed(java.util.List.of("x")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void userDefinedModelRouterWins() {
        ModelRouter mine = new ModelRouter() {
            @Override
            public ChatClient chatClient(Tier tier, DataClass dataClass) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EmbeddingModel embeddingModel(DataClass dataClass) {
                throw new UnsupportedOperationException();
            }
        };
        runner.withBean(ModelRouter.class, () -> mine).run(context -> {
            assertThat(context).hasSingleBean(ModelRouter.class);
            assertThat(context.getBean(ModelRouter.class)).isSameAs(mine);
        });
    }

    @Test
    void metricsAreRegisteredOnlyWhenAMeterRegistryExists() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            assertThat(context.getBean(RouterMetrics.class)).isInstanceOf(MicrometerRouterMetrics.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            RouterMetrics metrics = context.getBean(RouterMetrics.class);
            metrics.call("tier0", "ok");
            metrics.usage("tier0", 3, 4, 5);
            assertThat(registry.get("router.calls")
                            .tag("tier", "tier0")
                            .tag("outcome", "ok")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("router.cost.usd_micros").counter().count()).isEqualTo(5.0);
        });
        runner.run(context -> assertThat(context.getBean(RouterMetrics.class)).isSameAs(RouterMetrics.NOOP));
    }

    @Test
    void aStringRedisTemplateSelectsTheValkeyGuardAndAUserGuardStillWins() {
        StringRedisTemplate template = new StringRedisTemplate(
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory());
        runner.withBean(StringRedisTemplate.class, () -> template)
                .run(context -> assertThat(context.getBean(CostGuard.class)).isInstanceOf(ValkeyCostGuard.class));

        CostGuard mine = new InMemoryCostGuard(1, java.time.Clock.systemUTC());
        runner.withBean(StringRedisTemplate.class, () -> template)
                .withBean(CostGuard.class, () -> mine)
                .run(context -> assertThat(context.getBean(CostGuard.class)).isSameAs(mine));
    }

    @Test
    void dailyCapDefaultsToSeventyCentsAndIsConfigurable() {
        runner.run(context -> assertThat(context.getBean(RouterProperties.class).dailyCapUsdMicros())
                .isEqualTo(700_000L));
        runner.withPropertyValues("saiman.router.daily-cap-usd-micros=42")
                .run(context -> assertThat(
                                context.getBean(RouterProperties.class).dailyCapUsdMicros())
                        .isEqualTo(42L));
    }

    @Test
    void withBootsRealRedisAutoConfigurationTheValkeyGuardIsSelected() {
        // A wrong afterName is silently ignored, so this is the only guard on the ordering.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
                        ModelRouterAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(StringRedisTemplate.class);
                    assertThat(context.getBean(CostGuard.class)).isInstanceOf(ValkeyCostGuard.class);
                });
    }
}
