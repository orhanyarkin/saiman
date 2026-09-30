package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code seller.disclosures.*}.
 *
 * @param source {@code fixture} (default, M1 demo data) or {@code rag} (retrieval + model router);
 *     read by {@code @ConditionalOnProperty}, kept here so it is documented in one place
 * @param summaryCacheTtl how long a generated summary stays cached; the cache key already contains
 *     the corpus version, so this only bounds staleness of the wording, not of the facts
 */
@ConfigurationProperties("seller.disclosures")
public record DisclosureProperties(
        @DefaultValue("fixture") String source,
        @DefaultValue("6h") Duration summaryCacheTtl) {}
