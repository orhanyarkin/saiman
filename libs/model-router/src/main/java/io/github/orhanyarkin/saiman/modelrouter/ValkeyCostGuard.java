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
 * router:cost:{yyyy-MM-dd UTC}}, value in USD micro-dollars (settled costs plus outstanding
 * reservations). A new UTC day is a new key, so the cap rolls over without a job; keys expire after
 * two days. Reserving and settling are single Lua scripts, so check-and-add is atomic across
 * processes and a crash can never leave a counter without a TTL.
 *
 * <p>Fails closed: if Valkey is unreachable, or the counter is not a non-negative number, the
 * exception propagates and the call is not made.
 */
public final class ValkeyCostGuard implements CostGuard {

    static final String KEY_PREFIX = "router:cost:";
    private static final Duration KEY_TTL = Duration.ofDays(2);
    private static final long REFUSED = -1;
    private static final long CORRUPT = -2;

    /** INCRBY the estimate; undo and refuse if over the cap or the counter was already negative. */
    private static final RedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local est = tonumber(ARGV[1])
            local v = redis.call('INCRBY', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
            if v - est < 0 then redis.call('DECRBY', KEYS[1], ARGV[1]); return -2 end
            if v > tonumber(ARGV[2]) then redis.call('DECRBY', KEYS[1], ARGV[1]); return -1 end
            return v
            """, Long.class);

    /** Signed adjustment, clamped so the day's total never goes below zero. */
    private static final RedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            local v = redis.call('INCRBY', KEYS[1], ARGV[1])
            if v < 0 then redis.call('SET', KEYS[1], '0', 'KEEPTTL'); v = 0 end
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
    public Reservation reserve(Money estimate) {
        LocalDate day = today();
        Long result = redis.execute(
                RESERVE,
                List.of(key(day)),
                Long.toString(estimate.atomicUnits()),
                Long.toString(capUsdMicros),
                Long.toString(KEY_TTL.toSeconds()));
        if (result == null) {
            throw new IllegalStateException("Valkey returned no value for the cost reservation");
        }
        if (result == REFUSED) {
            throw new DailyCapExceededException("daily model cost cap reached (" + capUsdMicros + " USD micros)");
        }
        if (result == CORRUPT) {
            throw new IllegalStateException("the daily cost counter in Valkey is negative");
        }
        return new Reservation(day, estimate);
    }

    @Override
    public void settle(Reservation reservation, Money actual) {
        long delta = actual.atomicUnits() - reservation.estimate().atomicUnits();
        if (delta == 0) {
            return;
        }
        redis.execute(
                SETTLE, List.of(key(reservation.day())), Long.toString(delta), Long.toString(KEY_TTL.toSeconds()));
    }

    @Override
    public Money todayTotal() {
        String value = redis.opsForValue().get(todayKey());
        if (value == null) {
            return Money.usdMicros(0);
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            // never echo the stored value
            throw new IllegalStateException("the daily cost counter in Valkey is not a number");
        }
        if (parsed < 0) {
            throw new IllegalStateException("the daily cost counter in Valkey is negative");
        }
        return Money.usdMicros(parsed);
    }

    String todayKey() {
        return key(today());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String key(LocalDate day) {
        return KEY_PREFIX + day;
    }
}
