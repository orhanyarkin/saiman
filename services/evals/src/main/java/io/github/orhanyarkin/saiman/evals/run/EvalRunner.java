package io.github.orhanyarkin.saiman.evals.run;

import io.github.orhanyarkin.saiman.evals.EvalsProperties;
import io.github.orhanyarkin.saiman.evals.answers.AnswerRunner;
import io.github.orhanyarkin.saiman.evals.golden.GoldenSet;
import io.github.orhanyarkin.saiman.evals.golden.GoldenSet.GoldenItem;
import io.github.orhanyarkin.saiman.evals.golden.GoldenSetLoader;
import io.github.orhanyarkin.saiman.evals.golden.Kind;
import io.github.orhanyarkin.saiman.evals.ingest.IngestClient;
import io.github.orhanyarkin.saiman.evals.metrics.Disclosures;
import io.github.orhanyarkin.saiman.evals.metrics.Percentile;
import io.github.orhanyarkin.saiman.evals.metrics.RankingMetrics;
import io.github.orhanyarkin.saiman.evals.report.AnswerReport;
import io.github.orhanyarkin.saiman.evals.report.EvalReport;
import io.github.orhanyarkin.saiman.evals.report.EvalReport.ItemResult;
import io.github.orhanyarkin.saiman.evals.report.ReportWriter;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Tier R of the eval harness (ADR-0025): sends every RETRIEVAL and FRESHNESS question of the golden set to
 * ingest, scores the disclosure-level ranking and writes the report. ANSWER and UNANSWERABLE items are scored by
 * the answer tier (Tier A, optional) and only counted here when it is off. The runner is always a bean; the thin command line
 * entry point that calls it is conditional ({@code saiman.evals.run-on-startup}).
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    static final int RETRIEVAL_K = 10;
    static final int FRESHNESS_N = 5;

    private final EvalsProperties properties;
    private final IngestClient ingest;
    private final ReportWriter writer;
    private final Clock clock;
    private final ObjectProvider<AnswerRunner> answers;

    public EvalRunner(
            EvalsProperties properties, IngestClient ingest, Clock clock, ObjectProvider<AnswerRunner> answers) {
        this.answers = answers;
        this.properties = properties;
        this.ingest = ingest;
        this.writer = new ReportWriter();
        this.clock = clock;
    }

    /** Runs the evaluation, writes the report files and returns the report (also when items failed). */
    public EvalReport run() {
        GoldenSet golden = GoldenSetLoader.load(properties.goldenSet());
        log.info(
                "golden set loaded: {} items, labelled for corpus {}",
                golden.items().size(),
                golden.corpus().corpusVersion());

        List<ItemResult> results = new ArrayList<>();
        LiveCorpus live = new LiveCorpus();
        for (GoldenItem item : golden.items()) {
            if (item.kind() == Kind.RETRIEVAL || item.kind() == Kind.FRESHNESS) {
                results.add(evaluate(item, live));
            }
        }

        String liveVersion = live.version;
        boolean stale =
                liveVersion != null && !liveVersion.equals(golden.corpus().corpusVersion());
        if (stale) {
            log.warn(
                    "corpus version {} differs from the golden set's {}: relabel (services/evals/README.md)",
                    liveVersion,
                    golden.corpus().corpusVersion());
        }
        AnswerRunner answerRunner = answers.getIfAvailable();
        AnswerReport answerReport = answerRunner == null ? null : answerRunner.run(golden);
        Map<Kind, Integer> skipped = new EnumMap<>(Kind.class);
        for (Kind kind : List.of(Kind.ANSWER, Kind.UNANSWERABLE)) {
            int n = golden.items(kind).size();
            if (n > 0 && answerReport == null) {
                skipped.put(kind, n);
            }
        }
        EvalReport report = new EvalReport(
                Instant.now(clock).toString(),
                properties.gitSha(),
                properties.label(),
                properties.retrieval().topK(),
                golden.corpus().corpusVersion(),
                liveVersion,
                live.watermark,
                stale,
                summarize(results),
                results,
                skipped,
                results.size(),
                costNote(results.size()),
                answerReport);
        Path markdown = writer.write(report, Path.of(properties.outputDir()));
        log.info("eval report written: {} ({} items, {} failed)", markdown, results.size(), report.errors());
        return report;
    }

    private ItemResult evaluate(GoldenItem item, LiveCorpus live) {
        long start = System.nanoTime();
        try {
            RetrieveResponse response = ingest.retrieve(new RetrieveRequest(
                    item.question(),
                    List.of(item.ticker()),
                    properties.retrieval().topK()));
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (live.version == null) {
                live.version = response.corpusVersion();
                live.watermark = response.corpusWatermark().toString();
            }
            List<Long> ranking = Disclosures.dedupe(
                    response.chunks().stream().map(RetrievedChunk::chunkId).toList());
            return new ItemResult(
                    item.id(),
                    item.kind(),
                    item.ticker(),
                    item.question(),
                    ranking,
                    score(item, ranking),
                    millis,
                    null);
        } catch (RuntimeException e) {
            long millis = (System.nanoTime() - start) / 1_000_000;
            log.warn("item {} failed: {}", item.id(), e.getClass().getSimpleName());
            return new ItemResult(
                    item.id(),
                    item.kind(),
                    item.ticker(),
                    item.question(),
                    List.of(),
                    Map.of(),
                    millis,
                    e.getClass().getSimpleName());
        }
    }

    static Map<String, Double> score(GoldenItem item, List<Long> ranking) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (item.kind() == Kind.RETRIEVAL) {
            Map<Long, Integer> relevant = item.expected().relevant();
            metrics.put("recall@10", RankingMetrics.recallAtK(ranking, relevant, RETRIEVAL_K));
            metrics.put("mrr@10", RankingMetrics.reciprocalRankAtK(ranking, relevant, RETRIEVAL_K));
            metrics.put("ndcg@10", RankingMetrics.ndcgAtK(ranking, relevant, RETRIEVAL_K));
        } else {
            List<Long> latest = item.expected().latest();
            metrics.put("recency@5", RankingMetrics.recencyAtN(ranking, latest, FRESHNESS_N));
            metrics.put("latestHit@5", RankingMetrics.latestHitAtN(ranking, latest, FRESHNESS_N));
            metrics.put("ndcg@10", RankingMetrics.ndcgAtK(ranking, RankingMetrics.recencyGrades(latest), RETRIEVAL_K));
        }
        return metrics;
    }

    static Map<Kind, Map<String, Double>> summarize(List<ItemResult> results) {
        Map<Kind, Map<String, Double>> summary = new EnumMap<>(Kind.class);
        for (Kind kind : List.of(Kind.RETRIEVAL, Kind.FRESHNESS)) {
            List<ItemResult> ofKind =
                    results.stream().filter(r -> r.kind() == kind).toList();
            if (ofKind.isEmpty()) {
                continue;
            }
            List<ItemResult> ok = ofKind.stream().filter(r -> r.error() == null).toList();
            Map<String, Double> row = new LinkedHashMap<>();
            row.put("n", (double) ofKind.size());
            row.put("errors", (double) (ofKind.size() - ok.size()));
            if (!ok.isEmpty()) {
                for (String metric : ok.get(0).metrics().keySet()) {
                    row.put(
                            metric,
                            ok.stream()
                                    .mapToDouble(r -> r.metrics().get(metric))
                                    .average()
                                    .orElse(0.0));
                }
                row.put("latencyP95Ms", (double) Percentile.nearestRank(
                        ok.stream().map(ItemResult::latencyMs).toList(), 95));
            }
            summary.put(kind, row);
        }
        return summary;
    }

    private static String costNote(int queries) {
        return "Tier R makes %d retrieval calls; each embeds one short query through ingest's model-router route "
                        .formatted(queries)
                + "(embeddings only, no generation, no x402 payment), about $0.00002 per run (ADR-0025). "
                + "The answer tier's cost, when it ran, is in its own section.";
    }

    /** The corpus version and watermark ingest reported on the first successful response. */
    private static final class LiveCorpus {
        @Nullable
        String version;

        @Nullable
        String watermark;
    }
}
