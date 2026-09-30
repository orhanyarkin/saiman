package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 200 concurrent callers through the real router against a slow fake model: the counter (settled
 * costs plus outstanding reservations) never exceeds the cap, at any moment, in-memory and Valkey.
 */
@Testcontainers
class RouterCapConcurrencyTests {

    private static final int CALLERS = 200;
    private static final long CAP = 10_000;
    /** 1000 in + 100 out on tier0 (gpt-5-nano): 100 + 50 micro-dollars. */
    private static final long ACTUAL_PER_CALL = 150;

    @Container
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.2-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(VALKEY.getHost(), VALKEY.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    private static final class SlowModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage("ok"))))
                    .metadata(ChatResponseMetadata.builder()
                            .usage(new DefaultUsage(1000, 100))
                            .build())
                    .build();
        }
    }

    private static void run(CostGuard guard) throws InterruptedException {
        var model = new SlowModel();
        var router = new DefaultModelRouter(
                RouterProperties.defaults().withDailyCapUsdMicros(CAP),
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
                guard,
                RouterMetrics.NOOP);
        var client = router.chatClient(Tier.TIER0, DataClass.PUBLIC);

        var granted = new AtomicInteger();
        var refused = new AtomicInteger();
        var maxSeen = new AtomicLong();
        var done = new AtomicBoolean();
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                maxSeen.accumulateAndGet(guard.todayTotal().atomicUnits(), Math::max);
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CALLERS; i++) {
                executor.execute(() -> {
                    try {
                        client.prompt().user("hi").call().content();
                        granted.incrementAndGet();
                    } catch (DailyCapExceededException expected) {
                        refused.incrementAndGet();
                    }
                });
            }
        }
        done.set(true);
        sampler.join();

        assertThat(granted.get() + refused.get()).isEqualTo(CALLERS);
        assertThat(refused.get()).isPositive(); // the cap really bit
        assertThat(model.calls.get()).isEqualTo(granted.get()); // refused callers never reached the model
        assertThat(maxSeen.get()).isLessThanOrEqualTo(CAP); // settled + reserved, at every sampled moment
        assertThat(guard.todayTotal()).isEqualTo(Money.usdMicros(granted.get() * ACTUAL_PER_CALL));
    }

    @Test
    void inMemoryGuardNeverExceedsTheCapUnderConcurrentCallers() throws Exception {
        run(new InMemoryCostGuard(CAP, Clock.systemUTC()));
    }

    @Test
    void valkeyGuardNeverExceedsTheCapUnderConcurrentCallers() throws Exception {
        template.delete(new ValkeyCostGuard(template, CAP, Clock.systemUTC()).todayKey());
        run(new ValkeyCostGuard(template, CAP, Clock.systemUTC()));
    }
}
