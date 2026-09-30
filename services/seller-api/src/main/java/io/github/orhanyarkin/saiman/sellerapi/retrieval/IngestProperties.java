package io.github.orhanyarkin.saiman.sellerapi.retrieval;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the ingest service lives and how patient the client is ({@code seller.ingest.*}). The base
 * URL comes from configuration only, never from a request.
 *
 * @param baseUrl e.g. {@code http://ingest:8083}; required when {@code seller.disclosures.source=rag}
 * @param connectTimeout TCP connect timeout
 * @param readTimeout per-attempt response timeout (retrieval embeds the query, so seconds not millis)
 * @param retryAttempts total attempts including the first (retries are jittered)
 * @param retryWait base wait between attempts
 * @param circuitOpenWait how long the circuit breaker stays open before probing
 */
@ConfigurationProperties("seller.ingest")
public record IngestProperties(
        @Nullable String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("200ms") Duration retryWait,
        @DefaultValue("30s") Duration circuitOpenWait) {}
