package io.github.orhanyarkin.saiman.shared.retrieval;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One chunk of a public disclosure, as returned by hybrid retrieval. {@code chunkId} is stable
 * (deterministic from the source document and the chunk number) and is what answers cite.
 *
 * @param chunkId {@code kap:<disclosureIndex>:<chunkNumber>}, e.g. {@code kap:1118495:0002}
 * @param ticker BIST ticker of the disclosing company
 * @param source corpus source, currently always {@code kap}
 * @param title disclosure subject
 * @param sourceUrl public link back to the disclosure ({@code https://www.kap.org.tr/tr/Bildirim/<index>})
 * @param publishedAt when KAP published the disclosure
 * @param retrievedAt when ingest fetched it
 * @param text the chunk text (an excerpt, never the full disclosure)
 * @param rrfScore reciprocal-rank-fusion score (higher is better)
 * @param vectorRank 1-based rank in the vector leg, or null if that leg did not return it
 * @param lexicalRank 1-based rank in the full-text leg, or null if that leg did not return it
 */
public record RetrievedChunk(
        String chunkId,
        String ticker,
        String source,
        String title,
        String sourceUrl,
        Instant publishedAt,
        Instant retrievedAt,
        String text,
        double rrfScore,
        @Nullable Integer vectorRank,
        @Nullable Integer lexicalRank) {}
