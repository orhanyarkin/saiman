package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import java.time.Instant;
import java.util.List;

/**
 * The paid disclosure summary response body ({@code GET /v1/disclosures/{ticker}/summary}).
 *
 * @param ticker the BIST ticker the summary is for
 * @param summary a short, human-readable disclosure summary
 * @param citations the source chunks the summary is grounded in
 * @param dataSource where the summary came from; {@code "fixture"} until M2 replaces {@link
 *     FixtureDisclosureSummaryService} with a RAG-backed implementation behind the same {@link
 *     DisclosureSummaryService} interface
 */
public record DisclosureSummaryResponse(String ticker, String summary, List<Citation> citations, String dataSource) {

    /**
     * One cited source chunk.
     *
     * @param chunkId the identifier of the retrieved chunk
     * @param sourceUrl the public URL the chunk was retrieved from (KAP or another public source)
     * @param retrievedAt when the chunk was retrieved
     */
    public record Citation(String chunkId, String sourceUrl, Instant retrievedAt) {}
}
