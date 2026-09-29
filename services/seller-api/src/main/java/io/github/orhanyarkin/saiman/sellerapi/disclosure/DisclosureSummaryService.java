package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/**
 * Produces a disclosure summary for a ticker, behind {@link DisclosureSummaryController}'s
 * {@code @RequiresPayment} endpoint.
 *
 * <p>{@link FixtureDisclosureSummaryService} is the only implementation in M1: three fixed BIST
 * tickers read from {@code classpath:fixtures/disclosures/*.json}. M2 replaces only this
 * implementation with one backed by the RAG pipeline over real KAP disclosures
 * (docs/design/m1-x402.md, "seller-api"); the controller and the response shape do not change.
 */
public interface DisclosureSummaryService {

    /**
     * Returns the disclosure summary for {@code ticker}.
     *
     * @param ticker the BIST ticker, already validated by the controller ({@code ^[A-Z0-9]{3,6}$})
     * @return the summary for {@code ticker}
     * @throws TickerNotFoundException if no summary exists for {@code ticker}
     */
    DisclosureSummaryResponse summaryFor(String ticker);
}
