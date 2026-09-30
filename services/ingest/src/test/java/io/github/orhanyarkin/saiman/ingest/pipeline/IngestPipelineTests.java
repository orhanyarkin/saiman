package io.github.orhanyarkin.saiman.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import io.github.orhanyarkin.saiman.ingest.mkk.FakeMkkServer.Reply;
import io.github.orhanyarkin.saiman.ingest.mkk.SyntheticKap;
import io.github.orhanyarkin.saiman.ingest.store.CursorRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.RestTestClient;

@ExtendWith(OutputCaptureExtension.class)
class IngestPipelineTests extends IngestIntegrationTests {

    @Autowired
    private IngestJob job;

    @Autowired
    private CursorRepository cursors;

    @Autowired
    private LockConnectionFactory locks;

    @Autowired
    private RestTestClient client;

    private String status(long index) {
        return jdbc.sql("SELECT status FROM source_document WHERE id = :id")
                .param("id", "kap:" + index)
                .query(String.class)
                .single();
    }

    private long chunksOf(long index) {
        Long n = jdbc.sql("SELECT count(*) FROM chunk WHERE document_id = :id")
                .param("id", "kap:" + index)
                .query(Long.class)
                .single();
        return n;
    }

    @Test
    void firstRunIndexesTheCorpusWithCorrectionsBlocksAndCancellationsApplied() {
        RunReport report = job.run();

        assertThat(report.aborted()).isFalse();
        assertThat(report.unknownTickers()).containsExactly("NOSUCH");
        assertThat(report.tickersDone()).isEqualTo(2);
        // FR class was filtered, the blocked disclosure was never fetched
        assertThat(MKK.countRequests("/disclosureDetail/1094000")).isZero();
        assertThat(MKK.countRequests("/disclosureDetail/1100500")).isZero();
        assertThat(MKK.countRequests("/disclosureDetail/1093000")).isEqualTo(1);

        assertThat(status(1_093_000)).isEqualTo("SUPERSEDED"); // by the CORR 1100000
        assertThat(status(1_093_500)).isEqualTo("INDEXED");
        assertThat(status(1_096_000)).isEqualTo("SUPERSEDED"); // by the CANC 1102000
        assertThat(status(1_100_000)).isEqualTo("INDEXED");
        assertThat(status(1_101_500)).isEqualTo("INDEXED");
        assertThat(status(1_102_000)).isEqualTo("SUPERSEDED"); // the cancellation notice itself is not indexed
        assertThat(chunksOf(1_102_000)).isZero();
        assertThat(count("source_document")).isEqualTo(8); // 6 THYAO + 2 ASELS
        assertThat(chunksOf(1_093_500)).isGreaterThan(1);
        assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE id !~ '^kap:[0-9]+:[0-9]{4}$'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(count("dead_letter")).isZero();
    }

    @Test
    void windowedPagingAdvancesByTheStepOverEmptyPagesAndStopsPastTheLastIndex() {
        job.run();

        List<String> pages = MKK.requests().stream()
                .filter(r -> r.contains("/disclosures?") && r.contains("companyId=1107"))
                .map(r -> r.replaceAll(".*disclosureIndex=(\\d+).*", "$1"))
                .toList();
        // 1091689: hits 1093000..1094000; next 1094001: window ends 1097001 -> 1096000; then 1096001 (empty ->
        // +2000 -> 1098001) reaches 1100000.. and so on, ending past lastDisclosureIndex 1105000.
        assertThat(pages).startsWith("1091689", "1094001", "1096001", "1098001");
        assertThat(pages).doesNotHaveDuplicates();
        assertThat(Long.parseLong(pages.get(pages.size() - 1))).isLessThanOrEqualTo(1_105_000L);
        assertThat(cursors.find("THYAO")).hasValueSatisfying(c -> {
            assertThat(c.done()).isTrue();
            assertThat(c.index()).isGreaterThan(1_105_000L);
        });
    }

    @Test
    void textIsCleanAndEmbeddingsNeverSeeMetadataOrMarkup() {
        job.run();

        assertThat(embeddings.texts()).isNotEmpty().allSatisfy(text -> {
            assertThat(text).doesNotContain("font-family").doesNotContain("internal note");
            assertThat(text).doesNotContain("sourceUrl").doesNotContain("documentId");
        });
        String all = String.join("\n", embeddings.texts());
        assertThat(all).contains("yolları").contains("İstanbul").contains("ısparta");
        assertThat(jdbc.sql("SELECT metadata ->> 'sourceUrl' FROM chunk WHERE id = 'kap:1101500:0000'")
                        .query(String.class)
                        .single())
                .isEqualTo("https://www.kap.org.tr/tr/Bildirim/1101500");
        assertThat(jdbc.sql("SELECT source_url FROM source_document WHERE id = 'kap:1101500'")
                        .query(String.class)
                        .single())
                .isEqualTo("https://www.kap.org.tr/tr/Bildirim/1101500");
    }

    @Test
    void secondRunChangesNothingAndCallsTheEmbeddingModelZeroTimes() {
        job.run();
        long documents = count("source_document");
        long chunks = count("chunk");
        int firstRunCalls = embeddings.calls();
        assertThat(firstRunCalls).isPositive();
        long detailCalls = MKK.countRequests("/disclosureDetail/");

        // a full rescan: cursors are forgotten, every document is met again
        cursors.deleteAll();
        RunReport second = job.run();

        assertThat(count("source_document")).isEqualTo(documents);
        assertThat(count("chunk")).isEqualTo(chunks);
        assertThat(embeddings.calls()).isEqualTo(firstRunCalls);
        assertThat(second.count(Outcome.INDEXED)).isZero();
        assertThat(second.count(Outcome.SKIPPED)).isPositive();
        assertThat(MKK.countRequests("/disclosureDetail/")).isEqualTo(detailCalls);

        // and with the cursors intact the tickers are not even scanned
        assertThat(job.run().tickersDone()).isZero();
    }

    @Test
    void aCrashBetweenEmbedAndFinalizeIsRepairedWithoutDuplicatesOrNewEmbeddings() {
        job.run();
        long chunks = count("chunk");
        int calls = embeddings.calls();
        // the state a crash leaves: chunks written, status still PENDING, cursor not yet advanced
        jdbc.sql("UPDATE source_document SET status = 'PENDING' WHERE id = 'kap:1101500'")
                .update();
        cursors.deleteAll();

        RunReport repair = job.run();

        assertThat(repair.count(Outcome.REPAIRED)).isEqualTo(1);
        assertThat(status(1_101_500)).isEqualTo("INDEXED");
        assertThat(count("chunk")).isEqualTo(chunks);
        assertThat(embeddings.calls()).isEqualTo(calls);
    }

    @Test
    void aCrashDuringEmbeddingLeavesPartialChunksThatAreRewrittenNotDuplicated() {
        job.run();
        long chunks = count("chunk");
        long before = chunksOf(1_093_500);
        jdbc.sql("UPDATE source_document SET status = 'PENDING' WHERE id = 'kap:1093500'")
                .update();
        jdbc.sql("DELETE FROM chunk WHERE id = 'kap:1093500:0001'").update();
        cursors.deleteAll();
        int calls = embeddings.calls();

        RunReport repair = job.run();

        assertThat(repair.count(Outcome.INDEXED)).isEqualTo(1);
        assertThat(embeddings.calls()).isGreaterThan(calls);
        assertThat(chunksOf(1_093_500)).isEqualTo(before);
        assertThat(count("chunk")).isEqualTo(chunks);
        assertThat(status(1_093_500)).isEqualTo("INDEXED");
    }

    @Test
    void transientMkkErrorsAreRetriedTransparently() {
        MKK.enqueue("/disclosureDetail/1093500", Reply.status(500));
        MKK.enqueue("/disclosureDetail/1093500", Reply.retryAfter(429, 0));

        job.run();

        assertThat(status(1_093_500)).isEqualTo("INDEXED");
        assertThat(MKK.countRequests("/disclosureDetail/1093500")).isEqualTo(3);
        assertThat(count("dead_letter")).isZero();
    }

    @Test
    void aDocumentFailingThreeTimesIsParkedAndRetryDlqReopensIt() {
        MKK.always("/disclosureDetail/1101500", Reply.status(404)); // not retried by the client
        job.run();

        assertThat(status(1_101_500)).isEqualTo("FAILED");
        assertThat(MKK.countRequests("/disclosureDetail/1101500")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT stage || '/' || attempts FROM dead_letter WHERE external_id = '1101500'")
                        .query(String.class)
                        .single())
                .isEqualTo("fetch/3");
        assertThat(status(1_100_000)).isEqualTo("INDEXED"); // the rest of the corpus is unaffected

        // a parked document is not retried by ordinary runs
        cursors.deleteAll();
        job.run();
        assertThat(MKK.countRequests("/disclosureDetail/1101500")).isEqualTo(3);

        // fixed upstream, then retried through the admin endpoint (the run continues in the background)
        MKK.clearAlways("/disclosureDetail/1101500");
        client.post()
                .uri("/internal/v1/admin/retry-dlq")
                .header("X-Saiman-Internal", "1")
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.reopened")
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(status(1_101_500)).isEqualTo("INDEXED"));
        assertThat(count("dead_letter")).isZero();
    }

    @Test
    void anEmbeddingFailureIsParkedAtTheEmbedStage() {
        embeddings.failing(true);

        job.run();

        assertThat(countByStatus("FAILED")).isPositive();
        assertThat(jdbc.sql("SELECT DISTINCT stage FROM dead_letter")
                        .query(String.class)
                        .list())
                .containsExactly("embed");
    }

    @Test
    void disclosuresBlockedLaterArePurgedFromAnAlreadyIndexedCorpus() {
        job.run();
        assertThat(chunksOf(1_101_500)).isPositive();
        MKK.blocked("[{\"blockedType\":\"Disclosure\",\"disclosureIndex\":1101500}]");

        job.run();

        assertThat(status(1_101_500)).isEqualTo("BLOCKED");
        assertThat(chunksOf(1_101_500)).isZero();
    }

    @Test
    void onlyOneRunAtATimeThanksToTheAdvisoryLock() throws Exception {
        try (Connection other = locks.open();
                PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
            lock.setLong(1, 0x5A1A_0001_0000_0001L);
            lock.execute();

            RunReport busy = job.run();

            assertThat(busy.alreadyRunning()).isTrue();
            assertThat(MKK.requests()).isEmpty();
        }
        assertThat(job.run().alreadyRunning()).isFalse();
    }

    @Test
    void theCredentialNeverReachesTheLogsOrTheDeadLetterTable(CapturedOutput output) {
        MKK.always("/disclosureDetail/1101500", Reply.status(400));

        job.run();

        assertThat(output.getAll()).doesNotContain(SyntheticKap.CREDENTIALS);
        assertThat(jdbc.sql("SELECT string_agg(error_message, ' ') FROM dead_letter")
                        .query(String.class)
                        .single())
                .doesNotContain(SyntheticKap.CREDENTIALS)
                .contains("status 400");
    }
}
