package io.github.orhanyarkin.saiman.evals.report;

import io.github.orhanyarkin.saiman.evals.golden.Kind;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Tier A (answer tier) results, deterministic scores only. Money is integer micro-USD; formatting happens in
 * the Markdown writer.
 *
 * @param stoppedOnCap the router day cap ended the tier early (remaining items are {@code NOT_RUN})
 * @param abortReason why the tier stopped before the end (auth failure, run guard), or null
 * @param attempted questions sent to the seller
 * @param outcomes count per {@code EvalOutcome} name (and {@code CALL_FAILED} for HTTP-level failures)
 * @param summary per kind: n, scored, rates and means (ANSWER: taskSuccess, factRecall, citationRecall;
 *     UNANSWERABLE: refusalCorrect; both: citationValidity over all returned citations)
 * @param totalCostUsdMicros sum of the seller-reported model cost
 * @param meanCostUsdMicros mean per attempted question, rounded half up
 * @param latencyP95Ms nearest-rank p95 of the per-question wall time (retries included)
 * @param items one entry per selected golden item, in file order
 */
public record AnswerReport(
        boolean stoppedOnCap,
        @Nullable String abortReason,
        int attempted,
        Map<String, Integer> outcomes,
        Map<Kind, Map<String, Double>> summary,
        long totalCostUsdMicros,
        long meanCostUsdMicros,
        long latencyP95Ms,
        List<AnswerItem> items) {

    /**
     * @param status {@code OK}, {@code NOT_RUN} or {@code CALL_FAILED:<http status>}
     * @param outcome the seller's outcome, null when no response was received
     * @param citations citations returned
     * @param validCitations of those, citations that pass the validity check
     * @param citationBasis {@code RETRIEVAL} or {@code GOLDEN_INDEXES} (fallback), see {@code AnswerScoring}
     * @param citationRecall ANSWER only: share of {@code expected.sources} cited
     * @param factRecall ANSWER only: share of required facts found in the answer
     * @param correct ANSWER: task success; UNANSWERABLE: refusal correct; null when not scorable
     */
    public record AnswerItem(
            String id,
            Kind kind,
            String ticker,
            String question,
            String status,
            @Nullable String outcome,
            int citations,
            int validCitations,
            @Nullable String citationBasis,
            @Nullable Double citationRecall,
            @Nullable Double factRecall,
            @Nullable Boolean correct,
            long costUsdMicros,
            long latencyMs) {}

    /** Items that ended in a failure the operator should look at (HTTP-level failure or an ERROR outcome). */
    public int errors() {
        return (int) items.stream()
                        .filter(i -> i.status().startsWith("CALL_FAILED") || "ERROR".equals(i.outcome()))
                        .count()
                + (abortReason == null ? 0 : 1);
    }
}
