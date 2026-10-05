package io.github.orhanyarkin.saiman.evals.answers;

import io.github.orhanyarkin.saiman.evals.EvalsProperties;
import io.github.orhanyarkin.saiman.evals.golden.GoldenSet;
import io.github.orhanyarkin.saiman.evals.golden.GoldenSet.GoldenItem;
import io.github.orhanyarkin.saiman.evals.golden.Kind;
import io.github.orhanyarkin.saiman.evals.ingest.IngestClient;
import io.github.orhanyarkin.saiman.evals.metrics.Disclosures;
import io.github.orhanyarkin.saiman.evals.metrics.Percentile;
import io.github.orhanyarkin.saiman.evals.report.AnswerReport;
import io.github.orhanyarkin.saiman.evals.report.AnswerReport.AnswerItem;
import io.github.orhanyarkin.saiman.shared.eval.EvalAnswerRequest;
import io.github.orhanyarkin.saiman.shared.eval.EvalAnswerResponse;
import io.github.orhanyarkin.saiman.shared.eval.EvalCitation;
import io.github.orhanyarkin.saiman.shared.eval.EvalOutcome;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier A: sends the ANSWER and UNANSWERABLE items (file order, at most {@code answers.max-questions}) to the
 * seller one at a time and scores them deterministically ({@link AnswerScoring}). Not a Spring-scanned bean on
 * its own: {@link SellerClientConfiguration} creates it only when {@code saiman.evals.answers.enabled} is true,
 * so with the tier off no seller object exists and no call can be made.
 */
public class AnswerRunner {

    private static final Logger log = LoggerFactory.getLogger(AnswerRunner.class);

    private final EvalsProperties properties;
    private final SellerClient seller;
    private final IngestClient ingest;

    public AnswerRunner(EvalsProperties properties, SellerClient seller, IngestClient ingest) {
        this.properties = properties;
        this.seller = seller;
        this.ingest = ingest;
    }

    public AnswerReport run(GoldenSet golden) {
        List<GoldenItem> selected = golden.items().stream()
                .filter(i -> i.kind() != Kind.RETRIEVAL && i.kind() != Kind.FRESHNESS)
                .limit(Math.max(0, properties.answers().maxQuestions()))
                .toList();
        Set<Long> goldenIndexes = goldenIndexes(golden);

        List<AnswerItem> items = new ArrayList<>();
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        long totalCost = 0;
        List<Long> latencies = new ArrayList<>();
        boolean stoppedOnCap = false;
        @Nullable String abortReason = null;
        int attempted = 0;

        for (GoldenItem item : selected) {
            if (stoppedOnCap || abortReason != null) {
                items.add(notRun(item));
                continue;
            }
            long start = System.nanoTime();
            try {
                EvalAnswerResponse response = seller.answer(new EvalAnswerRequest(item.ticker(), item.question()));
                attempted++;
                long millis = (System.nanoTime() - start) / 1_000_000;
                latencies.add(millis);
                totalCost += response.modelCostUsdMicros();
                outcomes.merge(response.outcome().name(), 1, Integer::sum);
                items.add(score(item, response, millis, goldenIndexes));
                if (response.outcome() == EvalOutcome.LLM_CAP
                        && properties.answers().stopOnCap()) {
                    stoppedOnCap = true;
                    log.warn("router day cap reached: the answer tier stops (answers.stop-on-cap)");
                }
            } catch (SellerAuthException | SellerUnavailableException e) {
                attempted++;
                abortReason = e.getMessage();
                log.error("answer tier aborted: {}", e.getMessage());
                items.add(failed(item, "CALL_FAILED:" + statusOf(e), (System.nanoTime() - start) / 1_000_000));
            } catch (SellerCallException e) {
                attempted++;
                long millis = (System.nanoTime() - start) / 1_000_000;
                latencies.add(millis);
                outcomes.merge("CALL_FAILED", 1, Integer::sum);
                items.add(failed(item, "CALL_FAILED:" + e.status(), millis));
            }
        }
        return new AnswerReport(
                stoppedOnCap,
                abortReason,
                attempted,
                outcomes,
                summarize(items),
                totalCost,
                AnswerScoring.meanMicros(totalCost, outcomeResponses(outcomes)),
                latencies.isEmpty() ? 0 : Percentile.nearestRank(latencies, 95),
                items);
    }

    private static int statusOf(RuntimeException e) {
        if (e instanceof SellerUnavailableException u) {
            return u.status();
        }
        return e instanceof SellerAuthException ? 401 : 0;
    }

    /** Questions that came back with a seller response (cost is only reported for those). */
    private static int outcomeResponses(Map<String, Integer> outcomes) {
        return outcomes.entrySet().stream()
                .filter(e -> !e.getKey().equals("CALL_FAILED"))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private AnswerItem score(GoldenItem item, EvalAnswerResponse response, long millis, Set<Long> goldenIndexes) {
        List<String> cited =
                response.citations().stream().map(EvalCitation::chunkId).toList();
        Allowed allowed = allowedIndexes(item, goldenIndexes);
        int valid = AnswerScoring.validCitations(cited, allowed.indexes());
        String answer = response.answer() == null ? "" : response.answer();
        Double citationRecall = null;
        Double factRecall = null;
        Boolean correct;
        Boolean relativeTimeFree = response.answer() == null ? null : AnswerScoring.relativeTimeFree(answer);
        List<String> hits = response.answer() == null ? List.of() : AnswerScoring.relativeTimeHits(answer);
        if (item.kind() == Kind.TEMPORAL) {
            // only an answer is judged; a refusal or a missing citation is reported, not a violation
            correct = response.outcome() == EvalOutcome.ANSWERED
                    ? !cited.isEmpty() && valid == cited.size() && Boolean.TRUE.equals(relativeTimeFree)
                    : null;
        } else if (item.kind() == Kind.ANSWER) {
            citationRecall = AnswerScoring.citationRecall(item.expected().sources(), cited);
            factRecall = AnswerScoring.factRecall(answer, item.expected().requiredFacts());
            boolean scorable = response.outcome() != EvalOutcome.LLM_CAP && response.outcome() != EvalOutcome.ERROR;
            correct = scorable
                    ? response.outcome() == EvalOutcome.ANSWERED
                            && factRecall == 1.0
                            && citationRecall == 1.0
                            && !cited.isEmpty()
                            && valid == cited.size()
                    : null;
        } else {
            correct = AnswerScoring.refusalCorrect(response.outcome());
        }
        return new AnswerItem(
                item.id(),
                item.kind(),
                item.ticker(),
                item.question(),
                "OK",
                response.outcome().name(),
                cited.size(),
                valid,
                allowed.basis(),
                citationRecall,
                factRecall,
                correct,
                relativeTimeFree,
                hits,
                response.modelCostUsdMicros(),
                millis);
    }

    private Allowed allowedIndexes(GoldenItem item, Set<Long> goldenIndexes) {
        try {
            Set<Long> retrieved = new HashSet<>(Disclosures.dedupe(ingest
                    .retrieve(new RetrieveRequest(
                            item.question(),
                            List.of(item.ticker()),
                            properties.retrieval().topK()))
                    .chunks()
                    .stream()
                    .map(RetrievedChunk::chunkId)
                    .toList()));
            return new Allowed(retrieved, "RETRIEVAL");
        } catch (RuntimeException e) {
            log.warn(
                    "retrieval for citation check failed ({}); falling back to golden-set indexes",
                    e.getClass().getSimpleName());
            return new Allowed(goldenIndexes, "GOLDEN_INDEXES");
        }
    }

    private static Set<Long> goldenIndexes(GoldenSet golden) {
        Set<Long> all = new HashSet<>();
        for (GoldenItem item : golden.items()) {
            all.addAll(item.expected().relevant().keySet());
            all.addAll(item.expected().latest());
            all.addAll(item.expected().sources());
        }
        return all;
    }

    private static AnswerItem notRun(GoldenItem item) {
        return new AnswerItem(
                item.id(),
                item.kind(),
                item.ticker(),
                item.question(),
                "NOT_RUN",
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0,
                0);
    }

    private static AnswerItem failed(GoldenItem item, String status, long millis) {
        return new AnswerItem(
                item.id(),
                item.kind(),
                item.ticker(),
                item.question(),
                status,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0,
                millis);
    }

    static Map<Kind, Map<String, Double>> summarize(List<AnswerItem> items) {
        Map<Kind, Map<String, Double>> summary = new EnumMap<>(Kind.class);
        for (Kind kind : List.of(Kind.ANSWER, Kind.UNANSWERABLE, Kind.TEMPORAL)) {
            List<AnswerItem> ofKind =
                    items.stream().filter(i -> i.kind() == kind).toList();
            if (ofKind.isEmpty()) {
                continue;
            }
            List<AnswerItem> scored =
                    ofKind.stream().filter(i -> i.correct() != null).toList();
            Map<String, Double> row = new LinkedHashMap<>();
            row.put("n", (double) ofKind.size());
            row.put("scored", (double) scored.size());
            if (!scored.isEmpty()) {
                double success = scored.stream()
                        .filter(i -> Boolean.TRUE.equals(i.correct()))
                        .count();
                row.put(
                        switch (kind) {
                            case ANSWER -> "taskSuccess";
                            case UNANSWERABLE -> "refusalCorrect";
                            default -> "temporalSuccess";
                        },
                        success / scored.size());
                if (kind == Kind.ANSWER) {
                    row.put("factRecall", mean(scored, true));
                    row.put("citationRecall", mean(scored, false));
                }
            }
            List<AnswerItem> withText =
                    ofKind.stream().filter(i -> i.relativeTimeFree() != null).toList();
            if (kind != Kind.UNANSWERABLE && !withText.isEmpty()) {
                row.put(
                        "relativeTimeFree",
                        (double) withText.stream()
                                        .filter(i -> Boolean.TRUE.equals(i.relativeTimeFree()))
                                        .count()
                                / withText.size());
            }
            int citations = ofKind.stream().mapToInt(AnswerItem::citations).sum();
            if (citations > 0) {
                row.put(
                        "citationValidity",
                        (double) ofKind.stream()
                                        .mapToInt(AnswerItem::validCitations)
                                        .sum()
                                / citations);
            }
            summary.put(kind, row);
        }
        return summary;
    }

    private static double mean(List<AnswerItem> scored, boolean facts) {
        return scored.stream()
                .mapToDouble(i -> {
                    Double v = facts ? i.factRecall() : i.citationRecall();
                    return v == null ? 0.0 : v;
                })
                .average()
                .orElse(0.0);
    }

    private record Allowed(Set<Long> indexes, String basis) {}
}
