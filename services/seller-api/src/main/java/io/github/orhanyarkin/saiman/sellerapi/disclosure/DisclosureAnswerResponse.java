package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import java.time.Instant;
import java.util.List;

/**
 * The paid answer to a question about a company's disclosures ({@code POST
 * /v1/disclosures/{ticker}/questions}).
 *
 * @param ticker the BIST ticker asked about
 * @param question the question as asked
 * @param answer the model's answer, grounded in {@code citations}
 * @param citations at least two retrieved chunks the answer cites; rebuilt from retrieval, never from model output
 * @param dataSource always {@code kap-rag}
 * @param corpusAsOf the newest publication time in the indexed corpus
 */
public record DisclosureAnswerResponse(
        String ticker,
        String question,
        String answer,
        List<Citation> citations,
        String dataSource,
        Instant corpusAsOf) {

    /**
     * One cited chunk.
     *
     * @param chunkId stable chunk id, {@code kap:<disclosureIndex>:<chunkNumber>}
     * @param sourceUrl the public KAP page of the disclosure
     * @param title the disclosure subject
     * @param publishedAt when KAP published it
     * @param excerpt a short excerpt of the chunk text
     */
    public record Citation(String chunkId, String sourceUrl, String title, Instant publishedAt, String excerpt) {}
}
