package io.github.orhanyarkin.saiman.evals.golden;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The golden set (ADR-0025): metadata about disclosures only (indexes, dates, short facts), never KAP
 * text. Labels are tied to {@link Corpus#corpusVersion()}; the runner warns when the live corpus differs.
 */
public record GoldenSet(int version, Corpus corpus, List<GoldenItem> items) {

    public GoldenSet {
        items = List.copyOf(items);
    }

    public List<GoldenItem> items(Kind kind) {
        return items.stream().filter(item -> item.kind() == kind).toList();
    }

    /**
     * @param watermark newest publication time of the indexed corpus when the labels were made
     * @param corpusVersion the ingest {@code corpusVersion} token the labels belong to
     */
    public record Corpus(Instant watermark, String corpusVersion) {}

    /**
     * @param ticker BIST ticker the question is scoped to (also sent as the retrieval ticker filter)
     */
    public record GoldenItem(String id, Kind kind, String ticker, String question, Expected expected) {}

    /**
     * Expected results; which fields are set depends on the item kind (the loader enforces it).
     *
     * @param relevant RETRIEVAL: disclosure index to graded relevance (3 = the disclosure asked for, 2 = same
     *     subject, 1 = loosely related), in file order
     * @param latest FRESHNESS: the 5 newest indexed disclosures of the ticker, newest first
     * @param sources ANSWER: disclosure indexes a correct answer must cite
     * @param requiredFacts ANSWER: each entry lists acceptable spellings of one fact; the answer must contain one
     *     of them per entry
     * @param reason UNANSWERABLE: why the corpus cannot answer ({@code NOT_IN_CORPUS},
     *     {@code AFTER_CORPUS_WATERMARK})
     */
    public record Expected(
            Map<Long, Integer> relevant,
            List<Long> latest,
            List<Long> sources,
            List<List<String>> requiredFacts,
            @Nullable String reason) {

        public Expected {
            relevant = Collections.unmodifiableMap(new LinkedHashMap<>(relevant)); // keeps file order
            latest = List.copyOf(latest);
            sources = List.copyOf(sources);
            requiredFacts = List.copyOf(requiredFacts);
        }
    }
}
