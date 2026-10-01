package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.TestRouters;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** A dead Redis must stop model calls (fail closed), not let them through. */
class RedisFailClosedTests {

    @Test
    void deadRedisBlocksTheCallBeforeTheModelIsReached() {
        var config = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1), config);
        factory.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(factory);
            var guard = new RedisCostGuard(template, 700_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
            var chat = new FakeChatModel("ok");
            var embedding = new FakeEmbeddingModel(1536);
            var router = TestRouters.over(chat, embedding, guard);

            assertThatThrownBy(() -> guard.reserve(io.github.orhanyarkin.saiman.shared.money.Money.usdMicros(1)))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> router.chatClient(Tier.TIER0, DataClass.PUBLIC)
                            .prompt()
                            .user("hi")
                            .call()
                            .content())
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> router.embeddingModel(DataClass.PUBLIC).embed(java.util.List.of("hi")))
                    .isInstanceOf(RuntimeException.class);

            assertThat(chat.callCount()).isZero();
            assertThat(embedding.callCount()).isZero();
        } finally {
            factory.destroy();
        }
    }

    @Test
    void deadRedisBlocksAScopedCallBeforeTheModelIsReached() {
        var config = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1), config);
        factory.afterPropertiesSet();
        try {
            var scoped = new RedisScopedCostGuard(new StringRedisTemplate(factory));
            var chat = new FakeChatModel("ok");
            var router = new DefaultModelRouter(
                    io.github.orhanyarkin.saiman.modelrouter.RouterProperties.defaults(),
                    new ModelFactory() {
                        @Override
                        public org.springframework.ai.chat.model.ChatModel chatModel(RouterProperties.Route route) {
                            return chat;
                        }

                        @Override
                        public org.springframework.ai.embedding.EmbeddingModel embeddingModel(
                                RouterProperties.Embedding route) {
                            return new FakeEmbeddingModel(1536);
                        }
                    },
                    new InMemoryCostGuard(700_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z"))),
                    RouterMetrics.NOOP,
                    scoped,
                    io.micrometer.observation.ObservationRegistry.NOOP);

            assertThatThrownBy(() -> router.chatClient(Tier.TIER0, DataClass.PUBLIC)
                            .prompt()
                            .advisors(a -> a.param(RouterAdvisorParams.COST_SCOPE, "run-1"))
                            .user("hi")
                            .call()
                            .content())
                    .isInstanceOf(RuntimeException.class);

            assertThat(chat.callCount()).isZero();
        } finally {
            factory.destroy();
        }
    }
}
