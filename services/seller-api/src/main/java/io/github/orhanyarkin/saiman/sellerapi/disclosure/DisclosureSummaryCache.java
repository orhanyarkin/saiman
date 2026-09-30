package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valkey cache of generated summaries, keyed by {@code (ticker, corpusVersion)}.
 *
 * <p>The key uses the opaque {@code corpusVersion} from retrieval, never the watermark: the version
 * changes when a disclosure is blocked or superseded, so a purged disclosure can not keep being
 * served from here. A cache failure is never fatal (it is a miss / a skipped write): the router's
 * own Valkey-backed cost guard is what fails closed for spending.
 */
@Component
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class DisclosureSummaryCache {

    private static final Logger log = LoggerFactory.getLogger(DisclosureSummaryCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final DisclosureProperties properties;

    DisclosureSummaryCache(StringRedisTemplate redis, JsonMapper jsonMapper, DisclosureProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    Optional<DisclosureSummaryResponse> get(String ticker, String corpusVersion) {
        try {
            String json = redis.opsForValue().get(key(ticker, corpusVersion));
            return json == null
                    ? Optional.empty()
                    : Optional.of(jsonMapper.readValue(json, DisclosureSummaryResponse.class));
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

    /** The version is opaque input from another service, so it is hashed rather than trusted as key syntax. */
    static String key(String ticker, String corpusVersion) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(corpusVersion.getBytes(StandardCharsets.UTF_8));
            return "seller:disclosure-summary:" + ticker + ":" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
