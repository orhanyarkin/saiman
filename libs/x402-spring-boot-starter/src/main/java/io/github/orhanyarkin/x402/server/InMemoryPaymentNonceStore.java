package io.github.orhanyarkin.x402.server;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-instance {@link PaymentNonceStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>The default when no {@link org.springframework.data.redis.core.StringRedisTemplate} bean is
 * available. Replay protection does not survive a restart and is not shared across instances, so
 * this is only appropriate for a single-instance deployment or for tests; a WARN is logged once at
 * construction to make that visible. A background daemon thread sweeps expired claims every minute
 * so a long-running process does not leak memory for claims nobody ever releases.
 */
public final class InMemoryPaymentNonceStore implements PaymentNonceStore, Closeable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPaymentNonceStore.class);

    private final ConcurrentHashMap<String, Claim> claims = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ScheduledExecutorService cleaner;

    public InMemoryPaymentNonceStore() {
        this(Clock.systemUTC());
    }

    /** Package-visible: lets tests control time without waiting on real clock expiry. */
    InMemoryPaymentNonceStore(Clock clock) {
        this.clock = clock;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "x402-nonce-store-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        var _ = this.cleaner.scheduleAtFixedRate(this::sweepExpired, 1, 1, TimeUnit.MINUTES);
        log.warn("Using the in-memory x402 payment nonce store: replay protection is per-instance only and"
                + " does not survive a restart. Configure a StringRedisTemplate bean (Redis/Valkey) for"
                + " multi-instance deployments.");
    }

    @Override
    public @Nullable String claim(String key, Duration ttl) {
        Instant now = clock.instant();
        Instant expiry = now.plus(ttl);
        String token = UUID.randomUUID().toString();
        Claim candidate = new Claim(token, expiry);
        Claim previous = claims.putIfAbsent(key, candidate);
        if (previous == null) {
            return token;
        }
        if (previous.expiry().isBefore(now)) {
            // The previous claim already expired: attempt to atomically take it over. If another
            // thread wins this race, this call correctly reports "not claimed".
            return claims.replace(key, previous, candidate) ? token : null;
        }
        return null;
    }

    @Override
    public void release(String key, String token) {
        // Compare-and-delete: only remove the entry if it is still held by this exact token, so a
        // stale/late release can never delete a claim a different caller has since taken over.
        claims.computeIfPresent(key, (k, existing) -> token.equals(existing.token()) ? null : existing);
    }

    private void sweepExpired() {
        Instant now = clock.instant();
        claims.entrySet().removeIf(entry -> entry.getValue().expiry().isBefore(now));
    }

    @Override
    public void close() {
        cleaner.shutdownNow();
    }

    private record Claim(String token, Instant expiry) {}
}
