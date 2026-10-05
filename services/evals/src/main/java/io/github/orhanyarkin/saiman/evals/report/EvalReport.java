package io.github.orhanyarkin.saiman.evals.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.orhanyarkin.saiman.evals.golden.Kind;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One eval run (Tier R: retrieval and freshness). Serialised as {@code latest.json}; strings rather than
 * {@code Instant}s keep the file stable for diffs.
 *
 * @param generatedAt ISO-8601 instant of the run
 * @param gitSha commit of the stack under test, or {@code unknown}
 * @param label free-text run label ({@code baseline}, {@code after}, ...), may be empty
 * @param topK chunks requested per query
 * @param goldenCorpusVersion corpus version the labels were made for
 * @param liveCorpusVersion corpus version ingest reported during the run, or null if no query succeeded
 * @param corpusWatermark newest publication time of the live corpus, or null
 * @param corpusStale true when the two versions differ: labels may no longer match the corpus
 * @param summary per kind: metric name to mean, plus {@code n}, {@code errors} and {@code latencyP95Ms}
 * @param items one entry per scored item
 * @param skipped items loaded and validated but not scored in this tier, per kind
 * @param queries number of retrieval calls made (the only cost driver of Tier R)
 * @param costNote what the run cost and why
 * @param answers Tier A results; null (and absent from the JSON) when the tier did not run
 */
public record EvalReport(
        String generatedAt,
        String gitSha,
        String label,
        int topK,
        String goldenCorpusVersion,
        @Nullable String liveCorpusVersion,
        @Nullable String corpusWatermark,
        boolean corpusStale,
        Map<Kind, Map<String, Double>> summary,
        List<ItemResult> items,
        Map<Kind, Integer> skipped,
        int queries,
        String costNote,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable AnswerReport answers) {

    /**
     * @param ranking disclosure indexes in rank order (chunks deduplicated, first rank kept)
     * @param metrics metric name to value; empty when {@code error} is set
     * @param latencyMs wall time of the retrieval call including retries
     * @param error exception class name when the query failed, else null
     */
    public record ItemResult(
            String id,
            Kind kind,
            String ticker,
            String question,
            List<Long> ranking,
            Map<String, Double> metrics,
            long latencyMs,
            @Nullable String error) {}

    public int errors() {
        return (int) items.stream().filter(item -> item.error() != null).count()
                + (answers == null ? 0 : answers.errors());
    }
}
