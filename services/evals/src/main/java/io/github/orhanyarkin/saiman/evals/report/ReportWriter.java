package io.github.orhanyarkin.saiman.evals.report;

import io.github.orhanyarkin.saiman.evals.golden.Kind;
import io.github.orhanyarkin.saiman.evals.report.EvalReport.ItemResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Writes {@code latest.md}, {@code latest.json} and {@code runs/<date>-<sha>[-<label>].json}. */
public class ReportWriter {

    private static final Map<Kind, List<String>> COLUMNS = Map.of(
            Kind.RETRIEVAL, List.of("recall@10", "mrr@10", "ndcg@10"),
            Kind.FRESHNESS, List.of("recency@5", "latestHit@5", "ndcg@10"));

    private final JsonMapper json =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** Writes all three files into {@code outputDir} (created if missing) and returns the Markdown path. */
    public Path write(EvalReport report, Path outputDir) {
        try {
            Files.createDirectories(outputDir.resolve("runs"));
            String body = json.writeValueAsString(report) + "\n";
            Files.writeString(outputDir.resolve("latest.json"), body, StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("runs").resolve(runFileName(report)), body, StandardCharsets.UTF_8);
            Path markdown = outputDir.resolve("latest.md");
            Files.writeString(markdown, markdown(report), StandardCharsets.UTF_8);
            return markdown;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the eval report to " + outputDir, e);
        }
    }

    static String runFileName(EvalReport report) {
        String date = report.generatedAt().substring(0, 10);
        String label = report.label().isBlank() ? "" : "-" + report.label().replaceAll("[^A-Za-z0-9._-]", "_");
        String sha = report.gitSha().replaceAll("[^A-Za-z0-9]", "");
        return date + "-" + (sha.length() > 12 ? sha.substring(0, 12) : sha) + label + ".json";
    }

    static String markdown(EvalReport r) {
        StringBuilder md = new StringBuilder();
        md.append("# Retrieval eval (Tier R)\n\n");
        md.append("- Generated: ").append(r.generatedAt()).append('\n');
        md.append("- Git sha: `").append(r.gitSha()).append("`\n");
        if (!r.label().isBlank()) {
            md.append("- Label: ").append(r.label()).append('\n');
        }
        md.append("- Chunks requested per query (topK): ").append(r.topK()).append('\n');
        md.append("- Golden-set corpus version: `")
                .append(r.goldenCorpusVersion())
                .append("`\n");
        md.append("- Live corpus version: `")
                .append(r.liveCorpusVersion() == null ? "unavailable" : r.liveCorpusVersion())
                .append("`");
        if (r.corpusWatermark() != null) {
            md.append(" (newest disclosure ").append(r.corpusWatermark()).append(")");
        }
        md.append('\n');
        if (r.corpusStale()) {
            md.append("\n> **Warning: the corpus changed since the golden set was labelled.** "
                    + "Scores may be wrong; relabel with the SQL in `services/evals/README.md`.\n");
        }
        if (r.errors() > 0) {
            md.append("\n> **")
                    .append(r.errors())
                    .append(" item(s) failed** (ingest unreachable?) and are excluded " + "from the means below.\n");
        }

        md.append("\n## Summary by kind\n\n");
        md.append("| Kind | n | errors | metric | mean |\n|---|---:|---:|---|---:|\n");
        for (Kind kind : List.of(Kind.RETRIEVAL, Kind.FRESHNESS)) {
            Map<String, Double> summary = r.summary().get(kind);
            if (summary == null) {
                continue;
            }
            for (String column : COLUMNS.getOrDefault(kind, List.of())) {
                md.append("| ")
                        .append(kind)
                        .append(" | ")
                        .append(whole(summary.get("n")))
                        .append(" | ")
                        .append(whole(summary.get("errors")))
                        .append(" | ")
                        .append(column)
                        .append(" | ")
                        .append(number(summary.get(column)))
                        .append(" |\n");
            }
            md.append("| ")
                    .append(kind)
                    .append(" | ")
                    .append(whole(summary.get("n")))
                    .append(" | ")
                    .append(whole(summary.get("errors")))
                    .append(" | p95 latency (ms) | ")
                    .append(whole(summary.get("latencyP95Ms")))
                    .append(" |\n");
        }

        for (Kind kind : List.of(Kind.RETRIEVAL, Kind.FRESHNESS)) {
            List<ItemResult> items =
                    r.items().stream().filter(i -> i.kind() == kind).toList();
            if (items.isEmpty()) {
                continue;
            }
            List<String> columns = COLUMNS.getOrDefault(kind, List.of());
            md.append("\n## ").append(kind).append(" items\n\n| id | ticker | question |");
            columns.forEach(c -> md.append(' ').append(c).append(" |"));
            md.append(" top 5 disclosures | ms |\n|---|---|---|");
            columns.forEach(c -> md.append("---:|"));
            md.append("---|---:|\n");
            for (ItemResult item : items) {
                md.append("| ")
                        .append(item.id())
                        .append(" | ")
                        .append(item.ticker())
                        .append(" | ")
                        .append(item.question().replace("|", "\\|"))
                        .append(" |");
                if (item.error() != null) {
                    columns.forEach(c -> md.append(" - |"));
                    md.append(" error: ")
                            .append(item.error())
                            .append(" | ")
                            .append(item.latencyMs())
                            .append(" |\n");
                    continue;
                }
                columns.forEach(c ->
                        md.append(' ').append(number(item.metrics().get(c))).append(" |"));
                md.append(' ')
                        .append(item.ranking().stream()
                                .limit(5)
                                .map(String::valueOf)
                                .collect(Collectors.joining(", ")))
                        .append(" | ")
                        .append(item.latencyMs())
                        .append(" |\n");
            }
        }

        if (!r.skipped().isEmpty()) {
            md.append("\n## Not scored in this tier\n\n");
            r.skipped()
                    .forEach((kind, count) -> md.append("- ")
                            .append(kind)
                            .append(": ")
                            .append(count)
                            .append(" item(s) loaded and validated (answer tier)\n"));
        }
        md.append("\n## Cost\n\n").append(r.costNote()).append('\n');
        return md.toString();
    }

    private static String number(@Nullable Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String whole(@Nullable Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.0f", value);
    }
}
