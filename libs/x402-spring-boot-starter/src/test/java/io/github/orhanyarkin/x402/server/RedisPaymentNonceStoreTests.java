package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link RedisPaymentNonceStore} against a real Valkey server (Redis-protocol-compatible), per the
 * acceptance criteria: claim/replay/TTL/release-is-compare-and-delete.
 *
 * <p>No {@code @ServiceConnection} here: as of Spring Boot 4.1.1, neither {@code
 * spring-boot-testcontainers} nor {@code spring-boot-autoconfigure} ships a Redis {@code
 * ConnectionDetailsFactory} for a generic container (unlike Postgres/Kafka), so the connection is
 * wired directly with a plain {@link LettuceConnectionFactory} instead.
 */
@Testcontainers
class RedisPaymentNonceStoreTests {

    @Container
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.1.2-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;

    private RedisPaymentNonceStore store;

    @BeforeAll
    static void startConnectionFactory() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void stopConnectionFactory() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void createStore() {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        store = new RedisPaymentNonceStore(template);
    }

    @Test
    void claimSucceedsOnceThenFailsForAnUnexpiredKey() {
        String key = "x402:nonce:test:" + System.nanoTime();

        assertThat(store.claim(key, Duration.ofMinutes(1))).isNotNull();
        assertThat(store.claim(key, Duration.ofMinutes(1))).isNull();
    }

    @Test
    void claimSucceedsAgainAfterRedisExpiresTheKey() {
        String key = "x402:nonce:test:" + System.nanoTime();

        assertThat(store.claim(key, Duration.ofSeconds(1))).isNotNull();
        assertThat(store.claim(key, Duration.ofSeconds(1))).isNull();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() ->
                        assertThat(store.claim(key, Duration.ofSeconds(1))).isNotNull());
    }

    @Test
    void releaseAllowsAnImmediateReclaim() {
        String key = "x402:nonce:test:" + System.nanoTime();

        String token = store.claim(key, Duration.ofMinutes(1));
        assertThat(token).isNotNull();
        store.release(key, token);
        assertThat(store.claim(key, Duration.ofMinutes(1))).isNotNull();
    }

    @Test
    void releaseIsANoOpForAWrongToken() {
        String key = "x402:nonce:test:" + System.nanoTime();

        String token = store.claim(key, Duration.ofMinutes(1));
        assertThat(token).isNotNull();
        store.release(key, "not-the-real-token");

        // The key is still held: a second claim under the real token's owner still fails.
        assertThat(store.claim(key, Duration.ofMinutes(1))).isNull();
    }

    @Test
    void expiredClaimReclaimedBySomeoneElseIsNotDeletedByTheOriginalHoldersRelease() {
        String key = "x402:nonce:test:" + System.nanoTime();

        String tokenA = store.claim(key, Duration.ofSeconds(1));
        assertThat(tokenA).isNotNull();

        // A's claim expires; B claims the same key.
        String tokenB = await().atMost(Duration.ofSeconds(5))
                .until(() -> store.claim(key, Duration.ofMinutes(1)), java.util.Objects::nonNull);

        // A's release (using its own, now-stale token) must not delete B's claim.
        store.release(key, tokenA);

        // Proven by: nobody else can claim the key -- it is still held (by B).
        assertThat(store.claim(key, Duration.ofMinutes(1))).isNull();

        // And B itself can still release it.
        store.release(key, tokenB);
        assertThat(store.claim(key, Duration.ofMinutes(1))).isNotNull();
    }

    @Test
    void differentKeysClaimIndependently() {
        String keyA = "x402:nonce:test:a:" + System.nanoTime();
        String keyB = "x402:nonce:test:b:" + System.nanoTime();

        assertThat(store.claim(keyA, Duration.ofMinutes(1))).isNotNull();
        assertThat(store.claim(keyB, Duration.ofMinutes(1))).isNotNull();
    }
}
