package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Body of the free {@code GET /v1/tickers}: which BIST tickers the paid endpoints can answer about.
 *
 * @param tickers the tickers, sorted, at most {@link #MAX_TICKERS}
 * @param dataSource {@code fixture} or {@code kap-rag}, as in the paid responses
 */
public record TickerListResponse(List<Ticker> tickers, String dataSource) {

    /** Upper bound on entries: the response size stays bounded whatever the source returns. */
    public static final int MAX_TICKERS = 1_000;

    /** The same ticker shape the paid endpoints accept in their path. */
    static final Pattern TICKER = Pattern.compile("[A-Z0-9]{3,6}");

    /**
     * One ticker.
     *
     * @param ticker the BIST ticker, e.g. {@code THYAO}
     * @param documents the number of disclosures indexed for it
     */
    public record Ticker(String ticker, long documents) {}

    public TickerListResponse {
        tickers = List.copyOf(tickers);
    }

    /**
     * Builds a response from untrusted source entries: drops anything that is not a valid ticker
     * (the paid endpoints would refuse it anyway), dedupes, sorts and caps at {@link #MAX_TICKERS}.
     */
    static TickerListResponse of(List<Ticker> source, String dataSource) {
        Map<String, Ticker> byTicker = new TreeMap<>();
        for (Ticker t : source) {
            if (t != null && t.ticker() != null && TICKER.matcher(t.ticker()).matches()) {
                byTicker.putIfAbsent(t.ticker(), new Ticker(t.ticker(), Math.max(0, t.documents())));
            }
        }
        List<Ticker> clean = byTicker.values().stream().limit(MAX_TICKERS).toList();
        return new TickerListResponse(clean, dataSource);
    }
}
