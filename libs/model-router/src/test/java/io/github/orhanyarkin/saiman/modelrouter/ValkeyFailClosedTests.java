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

/** A dead Valkey must stop model calls (fail closed), not let them through. */
class ValkeyFailClosedTests {

    @Test
    void deadValkeyBlocksTheCallBeforeTheModelIsReached() {
        var config = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1), config);
        factory.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(factory);
            var guard = new ValkeyCostGuard(template, 700_000, new MutableClock(Instant.parse("2026-09-29T10:00:00Z")));
            var chat = new FakeChatModel("ok");
            var embedding = new FakeEmbeddingModel(1536);
            var router = TestRouters.over(chat, embedding, guard);

            assertThatThrownBy(guard::assertUnderCap).isInstanceOf(RuntimeException.class);
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
}
