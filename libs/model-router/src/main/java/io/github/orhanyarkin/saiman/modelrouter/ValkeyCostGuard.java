package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Daily counter shared by every service that talks to the same Valkey: key {@code
 * router:cost:{yyyy-MM-dd UTC}}, value in USD micro-dollars, updated with the atomic {@code INCRBY}.
 * A new UTC day is a new key, so the cap rolls over without a job; keys expire after two days.
 *
 * <p>Fails closed: if Valkey is unreachable the exception propagates and the call is not made.
 */
public final class ValkeyCostGuard implements CostGuard {

    static final String KEY_PREFIX = "router:cost:";
    private static final Duration KEY_TTL = Duration.ofDays(2);

    /**
     * INCRBY plus a TTL if the key has none, in one atomic script: a crash between the two steps can
     * never leave a counter that lives forever.
     */
    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
                    local v = redis.call('INCRBY', KEYS[1], ARGV[1])
                    if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
                    return v
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final long capUsdMicros;
    private final Clock clock;

    public ValkeyCostGuard(StringRedisTemplate redis, long capUsdMicros, Clock clock) {
        this.redis = redis;
        this.capUsdMicros = capUsdMicros;
        this.clock = clock;
    }

    @Override
    public void assertUnderCap() {
        if (read() >= capUsdMicros) {
            throw new DailyCapExceededException("daily model cost cap reached (" + capUsdMicros + " USD micros)");
        }
    }

    @Override
    public Money record(Money cost) {
        Long total = redis.execute(
                INCREMENT_WITH_TTL,
                List.of(todayKey()),
                Long.toString(cost.atomicUnits()),
                Long.toString(KEY_TTL.toSeconds()));
        if (total == null) {
            throw new IllegalStateException("Valkey returned no value for the cost counter update");
        }
        return Money.usdMicros(total);
    }

    @Override
    public Money todayTotal() {
        return Money.usdMicros(read());
    }

    String todayKey() {
        return KEY_PREFIX + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private long read() {
        String value = redis.opsForValue().get(todayKey());
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // never echo the stored value
            throw new IllegalStateException("the daily cost counter in Valkey is not a number");
        }
    }
}
