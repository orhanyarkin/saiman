package io.github.orhanyarkin.x402.server;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Multi-instance {@link PaymentNonceStore} backed by Redis (or Valkey) {@code SET NX PX}.
 *
 * <p>Used automatically when a {@link StringRedisTemplate} bean is available; see {@code
 * X402ServerAutoConfiguration}. {@link #claim(String, Duration)} maps to {@code SET key <token> NX
 * PX ttlMillis}, which is atomic on the Redis side. {@link #release(String, String)} is a Lua
 * script that only deletes the key if its current value still equals the given token (an atomic
 * compare-and-delete: {@code GET} then {@code DEL} as two separate commands would race against a
 * concurrent re-claim of an expired key).
 */
public final class RedisPaymentNonceStore implements PaymentNonceStore {

    private static final RedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisPaymentNonceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    @Override
    public @Nullable String claim(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(claimed) ? token : null;
    }

    @Override
    public void release(String key, String token) {
        redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), token);
    }
}
