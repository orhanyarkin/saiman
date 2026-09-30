package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.llm.Deadline;
import io.github.orhanyarkin.saiman.sellerapi.llm.LlmRunProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valkey cache of generated summaries, keyed by {@code (ticker, corpusVersion)}.
 *
 * <p>The key uses the opaque {@code corpusVersion} from retrieval, never the watermark: the version
 * changes when a disclosure is blocked or superseded, so a purged disclosure can not keep being
 * served from here. A cache failure is never fatal (it is a miss / a skipped write): the run guard
 * and the router's own Valkey-backed cost guard are what fail closed for spending.
 *
 * <p>A cached value is untrusted input (anyone who can reach Valkey can write it): on read it is
 * validated against the same rules a fresh summary must satisfy, and one that fails is a miss.
 *
 * <p>{@link #getOrGenerate} makes a miss single-flight per key and negative-caches a failed
 * generation, so a burst of requests for an uncached summary (or a summary the model can not
 * produce) costs one model run, not one per request.
 */
@Component
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class DisclosureSummaryCache {

    private static final Logger log = LoggerFactory.getLogger(DisclosureSummaryCache.class);

    private static final int MAX_CITATIONS = 50;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration LOCK_MARGIN = Duration.ofSeconds(5);

    /** Deletes the lock only if it is still ours (KEYS[1] lock key, ARGV[1] our token). */
    private static final RedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            String.join(
                    "\n",
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then",
                    "  return redis.call('DEL', KEYS[1])",
                    "end",
                    "return 0"),
            Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final DisclosureProperties properties;
    private final LlmRunProperties llm;

    DisclosureSummaryCache(
            StringRedisTemplate redis, JsonMapper jsonMapper, DisclosureProperties properties, LlmRunProperties llm) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.llm = llm;
    }

    /**
     * The cached summary, or the result of {@code generator} (run by exactly one of the concurrent
     * callers for this key; the others wait briefly for its result).
     *
     * @param requireTime run after the cache and negative-cache checks and BEFORE the lock is taken;
     *     throws {@link InsufficientTimeException} when this request has too little time left to
     *     generate. A request that can not generate must never become the winner, and that refusal
     *     is never recorded as a failure of the key (it says nothing about the ticker).
     * @throws ModelUnavailableException if a recent generation for this key failed (remembered for
     *     {@code seller.llm.negative-cache-ttl}), or the winner's result did not arrive in time
     */
    DisclosureSummaryResponse getOrGenerate(
            String ticker,
            String corpusVersion,
            Deadline deadline,
            Runnable requireTime,
            Supplier<DisclosureSummaryResponse> generator) {
        Optional<DisclosureSummaryResponse> hit = get(ticker, corpusVersion);
        if (hit.isPresent()) {
            return hit.get();
        }
        if (failedRecently(ticker, corpusVersion)) {
            throw new ModelUnavailableException();
        }
        requireTime.run();
        String token = UUID.randomUUID().toString();
        if (!tryLock(ticker, corpusVersion, token)) {
            return awaitWinner(ticker, corpusVersion, deadline);
        }
        try {
            Optional<DisclosureSummaryResponse> late = get(ticker, corpusVersion);
            if (late.isPresent()) {
                return late.get();
            }
            DisclosureSummaryResponse summary = generator.get();
            put(ticker, corpusVersion, summary);
            return summary;
        } catch (ModelUnavailableException | MalformedModelOutputException | InsufficientCitationsException failure) {
            rememberFailure(ticker, corpusVersion);
            throw failure;
        } finally {
            unlock(ticker, corpusVersion, token);
        }
    }

    private DisclosureSummaryResponse awaitWinner(String ticker, String corpusVersion, Deadline deadline) {
        Duration budget = llm.singleFlightWait().compareTo(deadline.remaining()) < 0
                ? llm.singleFlightWait()
                : deadline.remaining();
        long giveUpAt = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < giveUpAt) {
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Optional<DisclosureSummaryResponse> hit = get(ticker, corpusVersion);
            if (hit.isPresent()) {
                return hit.get();
            }
            if (failedRecently(ticker, corpusVersion)) {
                break;
            }
        }
        throw new ModelUnavailableException();
    }

    Optional<DisclosureSummaryResponse> get(String ticker, String corpusVersion) {
        try {
            String json = redis.opsForValue().get(key(ticker, corpusVersion));
            if (json == null) {
                return Optional.empty();
            }
            DisclosureSummaryResponse cached = jsonMapper.readValue(json, DisclosureSummaryResponse.class);
            if (!valid(cached, ticker)) {
                log.warn("cached summary failed validation; treated as a miss");
                return Optional.empty();
            }
            return Optional.of(cached);
        } catch (RuntimeException e) {
            log.warn("summary cache read failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    void put(String ticker, String corpusVersion, DisclosureSummaryResponse summary) {
        try {
            redis.opsForValue()
                    .set(
                            key(ticker, corpusVersion),
                            jsonMapper.writeValueAsString(summary),
                            properties.summaryCacheTtl());
        } catch (RuntimeException e) {
            log.warn("summary cache write failed: {}", e.getClass().getSimpleName());
        }
    }

    /** The same rules a freshly generated summary satisfies, re-checked on every cache read. */
    static boolean valid(DisclosureSummaryResponse cached, String ticker) {
        if (cached == null
                || !ticker.equals(cached.ticker())
                || !RagDisclosureSummaryService.DATA_SOURCE.equals(cached.dataSource())) {
            return false;
        }
        String summary = cached.summary();
        if (summary == null
                || summary.isBlank()
                || summary.length() > GroundedGenerator.MAX_TEXT_CHARS
                || !summary.equals(GroundedGenerator.scrubLinks(summary))) {
            return false;
        }
        List<DisclosureSummaryResponse.Citation> citations = cached.citations();
        if (citations == null || citations.isEmpty() || citations.size() > MAX_CITATIONS) {
            return false;
        }
        return citations.stream()
                .allMatch(c -> c != null
                        && c.chunkId() != null
                        && GroundedGenerator.CHUNK_ID.matcher(c.chunkId()).matches()
                        && c.sourceUrl() != null
                        && GroundedGenerator.KAP_URL.matcher(c.sourceUrl()).matches()
                        && c.retrievedAt() != null);
    }

    private boolean tryLock(String ticker, String corpusVersion, String token) {
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(
                            lockKey(ticker, corpusVersion),
                            token,
                            llm.deadline().plus(LOCK_MARGIN));
            return acquired == null || acquired;
        } catch (RuntimeException e) {
            // No lock is not a reason to refuse: the run guard fails closed if Valkey is really down.
            log.warn("summary lock unavailable: {}", e.getClass().getSimpleName());
            return true;
        }
    }

    private void unlock(String ticker, String corpusVersion, String token) {
        try {
            redis.execute(UNLOCK, List.of(lockKey(ticker, corpusVersion)), token);
        } catch (RuntimeException e) {
            log.warn("summary unlock failed: {}", e.getClass().getSimpleName());
        }
    }

    private boolean failedRecently(String ticker, String corpusVersion) {
        try {
            return redis.hasKey(failureKey(ticker, corpusVersion));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void rememberFailure(String ticker, String corpusVersion) {
        try {
            redis.opsForValue().set(failureKey(ticker, corpusVersion), "1", llm.negativeCacheTtl());
        } catch (RuntimeException e) {
            log.warn("summary negative-cache write failed: {}", e.getClass().getSimpleName());
        }
    }

    /** The version is opaque input from another service, so it is hashed rather than trusted as key syntax. */
    static String key(String ticker, String corpusVersion) {
        return "seller:disclosure-summary:" + ticker + ":" + digest(corpusVersion);
    }

    static String lockKey(String ticker, String corpusVersion) {
        return "seller:disclosure-summary-lock:" + ticker + ":" + digest(corpusVersion);
    }

    static String failureKey(String ticker, String corpusVersion) {
        return "seller:disclosure-summary-failed:" + ticker + ":" + digest(corpusVersion);
    }

    private static String digest(String corpusVersion) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(corpusVersion.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
