package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.shared.retrieval.IndexedTicker;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The free {@code GET /v1/tickers} in RAG mode: proxied from ingest, no payment, cached, bounded
 * and cleaned. Its own context (a long TTL) so the in-memory cache starts empty.
 */
@TestPropertySource(properties = "seller.disclosures.ticker-cache-ttl=1h")
class TickerCatalogEndpointTests extends RagTestBase {

    @Test
    void theCatalogIsFreeCachedCleanedAndSurvivesAnIngestOutageOnceCached() {
        // (Nothing cached and ingest down: see IngestTickerCatalogTests; the back-off would outlast
        // this test's first request.)
        INGEST.tickers(List.of(
                new IndexedTicker("THYAO", 12, 40),
                new IndexedTicker("GARAN", 3, 9),
                new IndexedTicker("bad ticker <script>", 1, 1),
                new IndexedTicker("THYAO", 99, 99),
                new IndexedTicker("ASELS", -4, 0)));

        client.get()
                .uri("/v1/tickers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(X402Headers.PAYMENT_REQUIRED)
                .expectHeader()
                .valueMatches("Cache-Control", ".*max-age=60.*")
                .expectBody()
                .jsonPath("$.dataSource")
                .isEqualTo("kap-rag")
                .jsonPath("$.tickers[*].ticker")
                .isEqualTo(List.of("ASELS", "GARAN", "THYAO"))
                .jsonPath("$.tickers[0].documents")
                .isEqualTo(0)
                .jsonPath("$.tickers[2].documents")
                .isEqualTo(12);
        int callsAfterFirstHit = INGEST.tickerCalls();

        // Served from memory: no further ingest call, even when ingest goes down.
        INGEST.failWith(500);
        for (int i = 0; i < 5; i++) {
            client.get().uri("/v1/tickers").exchange().expectStatus().isOk();
        }
        assertThat(INGEST.tickerCalls()).isEqualTo(callsAfterFirstHit);
        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(router.routerRequests()).isZero();
    }

    @Test
    void theResponseIsBoundedWhateverIngestReturns() {
        List<TickerListResponse.Ticker> many = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            many.add(new TickerListResponse.Ticker(String.format("T%04d", i), 1));
        }
        TickerListResponse response = TickerListResponse.of(many, "kap-rag");
        assertThat(response.tickers()).hasSize(TickerListResponse.MAX_TICKERS);
        assertThat(response.tickers().getFirst().ticker()).isEqualTo("T0000");
    }
}
