package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/**
 * The tickers this seller can answer about, for the free {@code GET /v1/tickers} catalogue (a buyer
 * agent's planner reads it before deciding what to pay for). One implementation per disclosure
 * source: the fixture tickers, or the ingest corpus.
 */
interface TickerCatalog {

    /**
     * The catalogue, sorted by ticker and bounded to {@link TickerListResponse#MAX_TICKERS}.
     *
     * @throws io.github.orhanyarkin.saiman.sellerapi.retrieval.RetrievalUnavailableException if the
     *     source is unavailable and nothing is cached
     */
    TickerListResponse tickers();
}
