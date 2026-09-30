package io.github.orhanyarkin.saiman.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import io.github.orhanyarkin.saiman.ingest.mkk.FakeMkkServer.Reply;
import io.github.orhanyarkin.saiman.ingest.mkk.SyntheticKap;
import io.github.orhanyarkin.saiman.ingest.retrieval.RetrievalRepository;
import io.github.orhanyarkin.saiman.ingest.store.CursorRepository;
import io.github.orhanyarkin.saiman.ingest.store.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Out-of-order corrections, correction chains, blocked-title scrubbing and the corpus version token. */
class SupersessionAndVersionTests extends IngestIntegrationTests {

    @Autowired
    private IngestJob job;

    @Autowired
    private CursorRepository cursors;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private RetrievalRepository retrieval;

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
    void anOriginalThatFailedBeforeItsCorrectionIsSupersededAndNeverIndexedBesideIt() {
        MKK.always("/disclosureDetail/1093000", Reply.status(404));

        job.run();

        assertThat(status(1_100_000)).isEqualTo("INDEXED"); // the correction
        assertThat(status(1_093_000)).isEqualTo("SUPERSEDED"); // was FAILED, now stale
        assertThat(chunksOf(1_093_000)).isZero();

        // upstream is fixed and the DLQ is retried: nothing to reopen, the stale original stays out
        MKK.clearAlways("/disclosureDetail/1093000");
        cursors.deleteAll();
        job.run();
        assertThat(status(1_093_000)).isEqualTo("SUPERSEDED");
        assertThat(chunksOf(1_093_000)).isZero();
    }

    @Test
    void anOriginalFinalizedAfterItsCorrectionEndsUpSuperseded() {
        job.run();
        // the state after a DLQ retry ordering: the correction is indexed, the original gets processed again
        jdbc.sql("UPDATE source_document SET status = 'PENDING' WHERE id = 'kap:1093000'")
                .update();
        cursors.deleteAll();

        job.run();

        assertThat(status(1_093_000)).isEqualTo("SUPERSEDED");
        assertThat(status(1_100_000)).isEqualTo("INDEXED");
    }

    @Test
    void aChainOfCorrectionsPointingAtTheOriginalKeepsOnlyTheLatest() {
        SyntheticKap.add(
                MKK,
                SyntheticKap.THYAO,
                1_101_000,
                "ODA",
                "Özel Durum Açıklaması (Genel)",
                "Düzeltme 2",
                "CORR",
                "1093000",
                "01.07.2023 10:00:00",
                SyntheticKap.page("Düzeltme", "İkinci düzeltme metni."));

        job.run();

        assertThat(status(1_101_000)).isEqualTo("INDEXED");
        assertThat(status(1_100_000)).isEqualTo("SUPERSEDED"); // CORR#1
        assertThat(status(1_093_000)).isEqualTo("SUPERSEDED");
        assertThat(chunksOf(1_101_000)).isPositive();
    }

    @Test
    void aChainProcessedInTheOppositeOrderConvergesToTheSameState() {
        SyntheticKap.add(
                MKK,
                SyntheticKap.THYAO,
                1_101_000,
                "ODA",
                "Özel Durum Açıklaması (Genel)",
                "Düzeltme 2",
                "CORR",
                "1093000",
                "01.07.2023 10:00:00",
                SyntheticKap.page("Düzeltme", "İkinci düzeltme metni."));
        job.run();
        // CORR#1 gets (re)processed after CORR#2 is indexed
        jdbc.sql("UPDATE source_document SET status = 'PENDING' WHERE id = 'kap:1100000'")
                .update();
        cursors.deleteAll();

        job.run();

        assertThat(status(1_100_000)).isEqualTo("SUPERSEDED");
        assertThat(status(1_101_000)).isEqualTo("INDEXED");
    }

    @Test
    void blockedDisclosuresLoseTheirTitleAndKeepOnlyThePublicLink() {
        job.run();
        MKK.blocked("[{\"blockedType\":\"Disclosure\",\"disclosureIndex\":1101500}]");

        job.run();

        assertThat(status(1_101_500)).isEqualTo("BLOCKED");
        assertThat(jdbc.sql("SELECT title FROM source_document WHERE id = 'kap:1101500'")
                        .query(String.class)
                        .single())
                .isEqualTo("[blocked]");
        assertThat(jdbc.sql("SELECT source_url FROM source_document WHERE id = 'kap:1101500'")
                        .query(String.class)
                        .single())
                .isEqualTo("https://www.kap.org.tr/tr/Bildirim/1101500");
        assertThat(chunksOf(1_101_500)).isZero();
    }

    // --- corpus version ------------------------------------------------------------------------

    @Test
    void aRerunThatChangesNothingLeavesTheCorpusVersionUnchanged() {
        job.run();
        String version = retrieval.corpusVersion();

        cursors.deleteAll();
        job.run(); // full rescan, everything already terminal
        job.run();

        assertThat(retrieval.corpusVersion()).isEqualTo(version);
    }

    @Test
    void purgingOrSupersedingANonNewestDisclosureChangesTheVersionButNotTheWatermark() throws Exception {
        job.run();
        String initial = retrieval.corpusVersion();
        var watermark = retrieval.corpusWatermark();

        MKK.blocked("[{\"blockedType\":\"Disclosure\",\"disclosureIndex\":1093500}]"); // old, not the newest
        job.run();
        String afterPurge = retrieval.corpusVersion();

        assertThat(afterPurge).isNotEqualTo(initial);
        assertThat(retrieval.corpusWatermark()).isEqualTo(watermark);

        documents.markSuperseded("kap:1100000");
        String afterSupersede = retrieval.corpusVersion();

        assertThat(afterSupersede).isNotEqualTo(afterPurge);
        assertThat(retrieval.corpusWatermark()).isEqualTo(watermark);
    }

    @Test
    void theVersionIsExposedOnTheRetrieveResponse() {
        job.run();

        String expected = retrieval.corpusVersion();

        client.post()
                .uri("/internal/v1/retrieve")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("query", "yolları", "tickers", java.util.List.of(), "topK", 3))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.corpusVersion")
                .isEqualTo(expected);
    }
}
