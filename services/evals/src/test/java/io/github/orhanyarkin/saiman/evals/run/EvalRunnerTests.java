package io.github.orhanyarkin.saiman.evals.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.orhanyarkin.saiman.evals.golden.Kind;
import io.github.orhanyarkin.saiman.evals.report.EvalReport;
import io.github.orhanyarkin.saiman.evals.report.EvalReport.ItemResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The runner end to end against a stub ingest: loader, client with retry, metrics and report files. */
@SpringBootTest(
        properties = {
            "saiman.evals.golden-set=classpath:golden/test-golden.yaml",
            "saiman.evals.git-sha=abc1234def5678",
            "saiman.evals.label=unit",
            "saiman.evals.ingest.retry-attempts=2",
            "saiman.evals.ingest.retry-wait=1ms"
        })
class EvalRunnerTests {

    private static final StubIngest INGEST = new StubIngest();

    @TempDir
    static Path out;

    @Autowired
    private EvalRunner runner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("saiman.evals.ingest.base-url", INGEST::baseUrl);
        registry.add("saiman.evals.output-dir", () -> out.toString());
    }

    @AfterAll
    static void stop() {
        INGEST.close();
    }

    @BeforeEach
    void stubs() {
        INGEST.reset();
        INGEST.answer("alfa sorusu", "kap:100:0001", "kap:100:0000", "kap:300:0000", "kap:200:0000");
        INGEST.answer("beta sorusu", "kap:300:0000");
        INGEST.failFirst("beta sorusu", 1); // one 503, then fine: the retry must absorb it
        INGEST.answer("ASELS son açıklamaları", "kap:12:0000", "kap:99:0000", "kap:11:0002");
    }

    private static ItemResult item(EvalReport report, String id) {
        return report.items().stream()
                .filter(i -> i.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void scoresRetrievalAndFreshnessAtDisclosureLevel() {
        EvalReport report = runner.run();

        assertThat(report.items()).hasSize(3);
        ItemResult alfa = item(report, "R-A");
        assertThat(alfa.ranking()).containsExactly(100L, 300L, 200L); // chunks of 100 collapsed, first rank kept
        assertThat(alfa.metrics().get("recall@10")).isEqualTo(1.0);
        assertThat(alfa.metrics().get("mrr@10")).isEqualTo(1.0);
        // DCG = 3/1 + 0 + 2/log2(4) = 4; IDCG = 3 + 2/log2(3) = 4.26186
        assertThat(alfa.metrics().get("ndcg@10")).isCloseTo(4.0 / 4.2618595071, within(1e-9));

        ItemResult fresh = item(report, "F-B");
        assertThat(fresh.metrics().get("recency@5")).isCloseTo(0.4, within(1e-12)); // 12 and 11 of {11..15}
        assertThat(fresh.metrics().get("latestHit@5")).isEqualTo(1.0);

        assertThat(report.summary().get(Kind.RETRIEVAL).get("n")).isEqualTo(2.0);
        assertThat(report.summary().get(Kind.RETRIEVAL).get("recall@10")).isEqualTo(1.0);
        assertThat(report.summary().get(Kind.FRESHNESS).get("latencyP95Ms")).isNotNull();
    }

    @Test
    void aTransientIngestErrorIsRetriedAndTheTickerFilterIsSent() {
        EvalReport report = runner.run();

        assertThat(item(report, "R-FLAKY").error()).isNull();
        assertThat(INGEST.calls("beta sorusu")).isEqualTo(2);
        assertThat(INGEST.requests())
                .anySatisfy(body ->
                        assertThat(body).contains("\"tickers\":[\"ASELS\"]").contains("\"topK\":10"));
    }

    @Test
    void answerItemsAreLoadedButNotScoredInThisTier() {
        EvalReport report = runner.run();

        assertThat(report.skipped()).containsEntry(Kind.ANSWER, 1).containsEntry(Kind.UNANSWERABLE, 1);
        assertThat(report.queries()).isEqualTo(3);
    }

    @Test
    void aMatchingCorpusVersionIsNotStaleAndADifferentOneIs() {
        assertThat(runner.run().corpusStale()).isFalse();

        INGEST.corpusVersion.set("v-changed");
        EvalReport stale = runner.run();
        assertThat(stale.corpusStale()).isTrue();
        assertThat(stale.liveCorpusVersion()).isEqualTo("v-changed");
    }

    @Test
    void aQueryThatKeepsFailingIsRecordedWithoutStoppingTheRun() {
        INGEST.answer("alfa sorusu", "kap:100:0000");
        INGEST.failFirst("alfa sorusu", 999);

        EvalReport report = runner.run();

        assertThat(item(report, "R-A").error()).isEqualTo("IngestUnavailableException");
        assertThat(item(report, "R-A").metrics()).isEmpty();
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.summary().get(Kind.RETRIEVAL).get("errors")).isEqualTo(1.0);
        assertThat(report.summary().get(Kind.RETRIEVAL).get("n")).isEqualTo(2.0);
        assertThat(item(report, "F-B").error()).isNull();
    }

    @Test
    void writesMarkdownAndJsonReportsAndARunFile() throws IOException {
        runner.run();

        String markdown = Files.readString(out.resolve("latest.md"), StandardCharsets.UTF_8);
        assertThat(markdown)
                .contains("# Retrieval eval (Tier R)")
                .contains("`abc1234def5678`")
                .contains("Label: unit")
                .contains("| RETRIEVAL | 2 | 0 | recall@10 | 1.000 |")
                .contains("## FRESHNESS items")
                .contains("| F-B | ASELS | ASELS son açıklamaları |")
                .contains("## Not scored in this tier")
                .contains("## Cost");
        String json = Files.readString(out.resolve("latest.json"), StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"gitSha\" : \"abc1234def5678\"")
                .contains("\"goldenCorpusVersion\" : \"v-golden\"")
                .contains("\"corpusStale\" : false");
        try (var runs = Files.list(out.resolve("runs"))) {
            assertThat(runs)
                    .anySatisfy(
                            file -> assertThat(file.getFileName().toString()).endsWith("-abc1234def56-unit.json"));
        }
    }
}
