package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.retrieval.IngestClient;
import io.github.orhanyarkin.saiman.shared.retrieval.IndexedTicker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The ingest corpus's tickers ({@code GET /internal/v1/tickers} through {@link IngestClient}, so
 * the same Host header, redirect and resilience rules apply), cached in memory for {@code
 * seller.disclosures.ticker-cache-ttl}.
 *
 * <p>The catalogue is free and unauthenticated, so the cache is what keeps it from being a lever
 * against ingest: at most one refresh runs at a time (others wait for it, then read its result),
 * so a burst of requests costs ingest one call per TTL. On a failed refresh the last good value is
 * served until it is replaced; with nothing cached the failure surfaces as a 503.
 */
@Component
@ConditionalOnProperty(name = "seller.disclosures.source", havingValue = "rag")
class IngestTickerCatalog implements TickerCatalog {

    static final Duration STALE_RETRY_AFTER = Duration.ofSeconds(5);

    private final IngestClient ingest;
    private final DisclosureProperties properties;
    private final Clock clock;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile @Nullable Cached cached;

    IngestTickerCatalog(IngestClient ingest, DisclosureProperties properties, Clock clock) {
        this.ingest = ingest;
        this.properties = properties;
        this.clock = clock;
    }

    private record Cached(TickerListResponse response, Instant expiresAt) {}

    @Override
    public TickerListResponse tickers() {
        Cached current = cached;
        if (current != null && clock.instant().isBefore(current.expiresAt())) {
            return current.response();
        }
        // A ReentrantLock rather than synchronized: it does not pin a virtual thread while the
        // refresh waits on the network.
        refreshLock.lock();
        try {
            current = cached;
            if (current != null && clock.instant().isBefore(current.expiresAt())) {
                return current.response();
            }
            List<IndexedTicker> indexed;
            try {
                indexed = ingest.tickers();
            } catch (RuntimeException e) {
                if (current != null) {
                    // Stale but good enough for a catalogue; retried after a short back-off so an
                    // ingest outage does not turn every catalogue request into an ingest call.
                    cached = new Cached(current.response(), clock.instant().plus(STALE_RETRY_AFTER));
                    return current.response();
                }
                throw e;
            }
            TickerListResponse response = TickerListResponse.of(
                    indexed.stream()
                            .map(t -> new TickerListResponse.Ticker(t.ticker(), t.documents()))
                            .toList(),
                    RagDisclosureSummaryService.DATA_SOURCE);
            cached = new Cached(response, clock.instant().plus(properties.tickerCacheTtl()));
            return response;
        } finally {
            refreshLock.unlock();
        }
    }
}
