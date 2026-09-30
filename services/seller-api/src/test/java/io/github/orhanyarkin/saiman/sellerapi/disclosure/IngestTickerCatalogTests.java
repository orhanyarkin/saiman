package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.retrieval.IngestClient;
import io.github.orhanyarkin.saiman.sellerapi.retrieval.RetrievalUnavailableException;
import io.github.orhanyarkin.saiman.shared.retrieval.IndexedTicker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Outage behaviour of the free ticker catalogue: one ingest call per back-off window, fast 503s. */
class IngestTickerCatalogTests {

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-30T12:00:00Z"));

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static DisclosureProperties properties() {
        return new DisclosureProperties("rag", Duration.ofHours(1), Duration.ofHours(1));
    }

    @Test
    void concurrentRequestsDuringAnOutageMakeAtMostOneIngestCallAndAreRefusedQuickly() throws Exception {
        IngestClient ingest = Mockito.mock(IngestClient.class);
        AtomicInteger calls = new AtomicInteger();
        Mockito.when(ingest.tickers()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            Thread.sleep(300); // a slow failing ingest: the old code queued every caller behind this
            throw new RetrievalUnavailableException();
        });
        MutableClock clock = new MutableClock();
        IngestTickerCatalog catalog = new IngestTickerCatalog(ingest, properties(), clock);

        int requests = 40;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        long begin = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        catalog.tickers();
                        return false;
                    } catch (RetrievalUnavailableException e) {
                        return true;
                    }
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get())
                        .as("every request is refused with the 503 exception")
                        .isTrue();
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);

        assertThat(calls).hasValue(1);
        assertThat(elapsed).isLessThan(IngestTickerCatalog.LOCK_WAIT.plusSeconds(1));
    }

    @Test
    void afterTheBackOffWindowIngestIsAskedAgainAndRecoveryIsServed() {
        IngestClient ingest = Mockito.mock(IngestClient.class);
        Mockito.when(ingest.tickers())
                .thenThrow(new RetrievalUnavailableException())
                .thenReturn(List.of(new IndexedTicker("THYAO", 3, 12)));
        MutableClock clock = new MutableClock();
        IngestTickerCatalog catalog = new IngestTickerCatalog(ingest, properties(), clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(catalog::tickers)
                .isInstanceOf(RetrievalUnavailableException.class);
        // Inside the window: refused without touching ingest.
        org.assertj.core.api.Assertions.assertThatThrownBy(catalog::tickers)
                .isInstanceOf(RetrievalUnavailableException.class);
        Mockito.verify(ingest, Mockito.times(1)).tickers();

        clock.advance(IngestTickerCatalog.STALE_RETRY_AFTER.plusMillis(1));
        assertThat(catalog.tickers().tickers())
                .extracting(TickerListResponse.Ticker::ticker)
                .containsExactly("THYAO");
        Mockito.verify(ingest, Mockito.times(2)).tickers();
    }
}
