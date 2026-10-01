package io.github.orhanyarkin.saiman.sellerapi.llm;

import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Bounds the model runs one payer, and unpaid traffic as a whole, can cause. Since ADR-0021 both
 * LLM endpoints use the x402 {@code upfront} flow, so their runs start after settlement and are
 * paid; the unsettled day budget below still protects any handler in the default flow, where the
 * model runs <em>before</em> the payment settles and a non-2xx answer costs the provider call and
 * pays nothing:
 *
 * <ul>
 *   <li>per payer: at most {@code maxInFlightPerPayer} runs at the same time and {@code
 *       maxRunsPerPayerPerHour} runs in any rolling hour;
 *   <li>globally: at most {@code maxUnsettledPerDay} runs per UTC day that started and whose payment
 *       has not settled. A run counts +1 when it starts and -1 when that request's payment settles
 *       ({@link X402PaymentSettledEvent}), so honest traffic does not consume the budget.
 * </ul>
 *
 * Every operation is one Lua script, so the checks and the counter updates are atomic across
 * concurrent requests and instances. All keys live under {@code seller:runs:}. When Redis is
 * unavailable the guard fails closed ({@link RunGuardUnavailableException}): no state, no model call.
 *
 * <p>The M2 residual risk (an attacker with many funded wallets using up the day's unsettled budget
 * and making the LLM endpoints answer 429 until midnight UTC) is closed for both LLM endpoints by
 * the upfront flow (ADR-0021): their runs are settled, so {@link #tryStart(String, boolean)} with
 * {@code settled = true} neither counts them nor refuses them on the day budget.
 */
@Component
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
public class UnsettledRunGuard {

    static final String PREFIX = "seller:runs:";

    /** Request attribute set once this request has started a run, so its settlement gives the slot back. */
    private static final String STARTED_ATTRIBUTE = UnsettledRunGuard.class.getName() + ".STARTED";

    private static final Logger log = LoggerFactory.getLogger(UnsettledRunGuard.class);

    private static final long INFLIGHT_KEY_TTL_SECONDS = 300;
    private static final long HOUR_KEY_TTL_SECONDS = 2 * 3600;
    private static final long DAY_KEY_TTL_SECONDS = 2 * 86_400;

    private static final long ALLOWED = 0;
    private static final long PAYER_BUSY = 1;
    private static final long PAYER_HOURLY = 2;
    // 3 = the day's unsettled budget is used up

    /**
     * KEYS: inflight, hourly zset, unsettled-day. ARGV: nowMillis, windowMillis, maxInFlight,
     * maxPerHour, maxUnsettled, member, inflightTtl, hourTtl, dayTtl, counted ('1': the run is unsettled
     * and counts against, and is checked against, the day budget; '0': already settled, skipped).
     */
    private static final RedisScript<Long> START = new DefaultRedisScript<>("""
            local inflight = tonumber(redis.call('GET', KEYS[1]) or '0')
            if inflight >= tonumber(ARGV[3]) then return 1 end
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', tonumber(ARGV[1]) - tonumber(ARGV[2]))
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then return 2 end
            local counted = ARGV[10] == '1'
            if counted then
              local unsettled = tonumber(redis.call('GET', KEYS[3]) or '0')
              if unsettled >= tonumber(ARGV[5]) then return 3 end
            end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[7])
            redis.call('ZADD', KEYS[2], ARGV[1], ARGV[6])
            redis.call('EXPIRE', KEYS[2], ARGV[8])
            if counted then
              redis.call('INCR', KEYS[3])
              redis.call('EXPIRE', KEYS[3], ARGV[9])
            end
            return 0
            """, Long.class);

    /** KEYS: a counter. Decrements it, never below zero. */
    private static final RedisScript<Long> DECREMENT = new DefaultRedisScript<>("""
            local v = tonumber(redis.call('GET', KEYS[1]) or '0')
            if v <= 1 then
              redis.call('DEL', KEYS[1])
              return 0
            end
            return redis.call('DECR', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final LlmRunProperties properties;
    private final Clock clock;

    public UnsettledRunGuard(StringRedisTemplate redis, LlmRunProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /** {@link #tryStart(String, boolean)} for a run whose payment has not settled yet. */
    public void tryStart(String payer) {
        tryStart(payer, false);
    }

    /**
     * Reserves a model run for {@code payer}; call before ANY model call and pair it with {@link
     * #finish(String)} in a {@code finally} block.
     *
     * @param settled whether the request's payment already settled ({@code
     *     X402PaymentContext.settled}, upfront flow): such a run is paid, so it neither counts against
     *     nor is refused by the unsettled day budget; the per-payer limits apply either way
     * @throws RunLimitExceededException if a limit is reached (nothing was reserved)
     * @throws RunGuardUnavailableException if the state store could not be reached (fail closed)
     */
    public void tryStart(String payer, boolean settled) {
        String normalised = payer.toLowerCase(Locale.ROOT);
        long now = clock.millis();
        Long verdict;
        try {
            verdict = redis.execute(
                    START,
                    List.of(inflightKey(normalised), hourlyKey(normalised), unsettledKey()),
                    Long.toString(now),
                    Long.toString(Duration.ofHours(1).toMillis()),
                    Integer.toString(properties.maxInFlightPerPayer()),
                    Integer.toString(properties.maxRunsPerPayerPerHour()),
                    Integer.toString(properties.maxUnsettledPerDay()),
                    UUID.randomUUID().toString(),
                    Long.toString(INFLIGHT_KEY_TTL_SECONDS),
                    Long.toString(HOUR_KEY_TTL_SECONDS),
                    Long.toString(DAY_KEY_TTL_SECONDS),
                    settled ? "0" : "1");
        } catch (RuntimeException e) {
            log.error("run guard unavailable: {}", e.getClass().getSimpleName());
            throw new RunGuardUnavailableException();
        }
        if (verdict == null) {
            throw new RunGuardUnavailableException();
        }
        if (verdict != ALLOWED) {
            log.warn(
                    "model run refused ({})",
                    verdict == PAYER_BUSY
                            ? "payer in-flight"
                            : verdict == PAYER_HOURLY ? "payer hourly" : "unsettled budget");
            throw new RunLimitExceededException();
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && !settled) {
            attributes.setAttribute(STARTED_ATTRIBUTE, true, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** Ends a run started with {@link #tryStart}: frees the payer's in-flight slot. Never throws. */
    public void finish(String payer) {
        try {
            redis.execute(DECREMENT, List.of(inflightKey(payer.toLowerCase(Locale.ROOT))));
        } catch (RuntimeException e) {
            // The key expires on its own (INFLIGHT_KEY_TTL_SECONDS); never mask the request's real outcome.
            log.error("run guard finish failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * The payment of a request that started a run settled: that run is no longer "unsettled". The
     * event is published synchronously on the request thread, so the request attribute set by
     * {@link #tryStart} is visible here; a settlement of a request that never started an unsettled
     * run (a cache hit, or an upfront request, settled before its run started) changes nothing.
     */
    @EventListener
    void onSettled(X402PaymentSettledEvent event) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null || attributes.getAttribute(STARTED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) == null) {
            return;
        }
        try {
            redis.execute(DECREMENT, List.of(unsettledKey()));
        } catch (RuntimeException e) {
            // Conservative direction: the unsettled counter stays high until the day rolls over.
            log.error("run guard settle accounting failed: {}", e.getClass().getSimpleName());
        }
    }

    private String unsettledKey() {
        return PREFIX + "unsettled:" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static String inflightKey(String payer) {
        return PREFIX + "inflight:" + payer;
    }

    private static String hourlyKey(String payer) {
        return PREFIX + "hourly:" + payer;
    }
}
