package io.github.orhanyarkin.saiman.shared.retrieval;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A hybrid-retrieval request. Validation lives in the record so every caller and the ingest
 * endpoint enforce the same bounds.
 *
 * @param query the natural-language question, 1-500 characters
 * @param tickers restrict to these BIST tickers ({@code ^[A-Z0-9]{3,6}$}); empty means any indexed ticker; at most 10
 * @param topK how many fused chunks to return, 1-20
 */
public record RetrieveRequest(String query, List<String> tickers, int topK) {

    public static final int MAX_QUERY_LENGTH = 500;
    public static final int MAX_TICKERS = 10;
    public static final int MAX_TOP_K = 20;

    private static final Pattern TICKER = Pattern.compile("[A-Z0-9]{3,6}");

    public RetrieveRequest {
        if (query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query must be 1-" + MAX_QUERY_LENGTH + " characters");
        }
        tickers = List.copyOf(tickers);
        if (tickers.size() > MAX_TICKERS) {
            throw new IllegalArgumentException("at most " + MAX_TICKERS + " tickers");
        }
        for (String ticker : tickers) {
            if (!TICKER.matcher(ticker).matches()) {
                throw new IllegalArgumentException("ticker must match [A-Z0-9]{3,6}");
            }
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be 1-" + MAX_TOP_K);
        }
    }
}
