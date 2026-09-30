package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Scoped guard shared by every process that talks to the same Valkey: one hash per scope, key
 * {@code run:{scopeId}:llm}, fields {@code budget} (pinned by the first reservation, {@code HSETNX})
 * and {@code spent} (USD micro-dollars). Reserving and settling are single Lua scripts, so
 * check-and-add is atomic and a crash never leaves a hash without a TTL (one day; a run is short).
 *
 * <p>Fails closed: if Valkey is unreachable, or the stored numbers are corrupt, the exception
 * propagates and the call is not made.
 */
public final class ValkeyScopedCostGuard implements ScopedCostGuard {

    static final Duration TTL = Duration.ofDays(1);
    private static final long REFUSED = -1;
    private static final long CORRUPT = -2;

    private static final RedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            redis.call('HSETNX', KEYS[1], 'budget', ARGV[2])
            local budget = tonumber(redis.call('HGET', KEYS[1], 'budget'))
            local spent = tonumber(redis.call('HGET', KEYS[1], 'spent') or '0')
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            if budget == nil or spent == nil or budget < 0 or spent < 0 then return -2 end
            local est = tonumber(ARGV[1])
            if spent + est > budget then return -1 end
            return redis.call('HINCRBY', KEYS[1], 'spent', ARGV[1])
            """, Long.class);

    private static final RedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            local v = redis.call('HINCRBY', KEYS[1], 'spent', ARGV[1])
            if v < 0 then redis.call('HSET', KEYS[1], 'spent', '0'); v = 0 end
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return v
            """, Long.class);

    private final StringRedisTemplate redis;

    public ValkeyScopedCostGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ScopeReservation reserve(String scopeId, Money estimate, Money budget) {
        ScopedCostGuard.requireValidScopeId(scopeId);
        Long result = redis.execute(
                RESERVE,
                List.of(key(scopeId)),
                Long.toString(estimate.atomicUnits()),
                Long.toString(budget.atomicUnits()),
                Long.toString(TTL.toSeconds()));
        if (result == null) {
            throw new IllegalStateException("Valkey returned no value for the scope reservation");
        }
        if (result == REFUSED) {
            throw new ScopeBudgetExceededException("model cost budget of the run is exhausted");
        }
        if (result == CORRUPT) {
            throw new IllegalStateException("the scope cost counter in Valkey is corrupt");
        }
        return new ScopeReservation(scopeId, estimate);
    }

    @Override
    public void settle(String scopeId, ScopeReservation reservation, Money actual) {
        long delta = actual.atomicUnits() - reservation.estimate().atomicUnits();
        if (delta == 0) {
            return;
        }
        ScopedCostGuard.requireValidScopeId(scopeId);
        redis.execute(SETTLE, List.of(key(scopeId)), Long.toString(delta), Long.toString(TTL.toSeconds()));
    }

    @Override
    public Money spent(String scopeId) {
        ScopedCostGuard.requireValidScopeId(scopeId);
        Object value = redis.opsForHash().get(key(scopeId), "spent");
        if (value == null) {
            return Money.usdMicros(0);
        }
        try {
            long parsed = Long.parseLong(value.toString());
            if (parsed >= 0) {
                return Money.usdMicros(parsed);
            }
        } catch (NumberFormatException e) {
            // fall through: never echo the stored value
        }
        throw new IllegalStateException("the scope cost counter in Valkey is corrupt");
    }

    static String key(String scopeId) {
        return "run:" + scopeId + ":llm";
    }
}
