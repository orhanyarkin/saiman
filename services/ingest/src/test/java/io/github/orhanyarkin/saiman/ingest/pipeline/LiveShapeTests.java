package io.github.orhanyarkin.saiman.ingest.pipeline;

import static io.github.orhanyarkin.saiman.ingest.mkk.FakeMkkServer.mkkError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import io.github.orhanyarkin.saiman.ingest.mkk.FakeMkkServer.Reply;
import io.github.orhanyarkin.saiman.ingest.store.CursorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Behaviour measured on the live MKK API (2026-09-30): errors that mean "nothing here", and resilience. */
@ExtendWith(OutputCaptureExtension.class)
class LiveShapeTests extends IngestIntegrationTests {

    private static final String MARKER = "MARKER-ER-MESSAGE";

    @Autowired
    private IngestJob job;

    @Autowired
    private CursorRepository cursors;

    private String status(long index) {
        return jdbc.sql("SELECT status FROM source_document WHERE id = :id")
                .param("id", "kap:" + index)
                .query(String.class)
                .single();
    }

    @Test
    void anEr005TailPageIsAnEmptyPageAndTheTickerCompletes(CapturedOutput output) {
        MKK.emptyListingAsEr005(true);

        RunReport report = job.run();

        assertThat(report.aborted()).isFalse();
        assertThat(report.failedTickers()).isEmpty();
        assertThat(report.tickersDone()).isEqualTo(2);
        assertThat(count("source_document")).isEqualTo(8); // nothing lost
        assertThat(countByStatus("INDEXED")).isEqualTo(5);
        assertThat(cursors.find("THYAO"))
                .hasValueSatisfying(c -> assertThat(c.done()).isTrue());
        assertThat(cursors.find("ASELS"))
                .hasValueSatisfying(c -> assertThat(c.done()).isTrue());
        assertThat(output.getAll()).doesNotContain(MARKER);
    }

    @Test
    void aTickerLeftAtTheEr005TailIsResumedAndFinishedOnTheNextRun() {
        MKK.emptyListingAsEr005(true);
        job.run();
        long documents = count("source_document");
        long chunks = count("chunk");
        int embedCalls = embeddings.calls();
        // the state the live crash left behind: everything stored, cursor at the tail, not done
        cursors.save("THYAO", 1_104_500L, false);

        RunReport resumed = job.run();

        assertThat(resumed.aborted()).isFalse();
        assertThat(cursors.find("THYAO")).hasValueSatisfying(c -> {
            assertThat(c.done()).isTrue();
            assertThat(c.index()).isGreaterThan(1_105_000L);
        });
        assertThat(count("source_document")).isEqualTo(documents);
        assertThat(count("chunk")).isEqualTo(chunks);
        assertThat(embeddings.calls()).isEqualTo(embedCalls);
    }

    @Test
    void anEr005OnTheDetailMarksTheDocumentMissingAndTheRunContinues() {
        MKK.always("/disclosureDetail/1101500", mkkError(400, "ER005", "Bildirim bulunamadi. " + MARKER));

        RunReport report = job.run();

        assertThat(report.aborted()).isFalse();
        assertThat(report.count(Outcome.MISSING)).isEqualTo(1);
        assertThat(status(1_101_500)).isEqualTo("MISSING");
        assertThat(status(1_100_000)).isEqualTo("INDEXED");
        assertThat(count("dead_letter")).isZero();
        assertThat(MKK.countRequests("/disclosureDetail/1101500")).isEqualTo(1); // never retried

        cursors.deleteAll();
        job.run();
        assertThat(MKK.countRequests("/disclosureDetail/1101500")).isEqualTo(1); // terminal: not asked again
    }

    @Test
    void aPersistentFailureOnOneTickersListingDoesNotStopTheOthers(CapturedOutput output) {
        MKK.failCompany(1107, mkkError(500, "ER999", "Sunucu hatasi " + MARKER));

        RunReport report = job.run();

        assertThat(report.aborted()).isFalse();
        assertThat(report.failedTickers()).containsExactly("THYAO");
        assertThat(report.tickersDone()).isEqualTo(1);
        assertThat(countByStatus("INDEXED")).isEqualTo(2); // ASELS
        assertThat(cursors.find("THYAO")).isEmpty();
        assertThat(output.getAll())
                .contains("Ticker THYAO failed")
                .contains("status 500")
                .doesNotContain(MARKER);

        // the next run resumes the failed ticker
        MKK.failCompany(1107, new Reply(200, "[]", java.util.Map.of()));
        MKK.reset();
        io.github.orhanyarkin.saiman.ingest.mkk.SyntheticKap.load(MKK);
        RunReport second = job.run();
        assertThat(second.failedTickers()).isEmpty();
        assertThat(cursors.find("THYAO"))
                .hasValueSatisfying(c -> assertThat(c.done()).isTrue());
    }

    @Test
    void aCredentialErrorCodeAbortsTheRunNamingTheCodeWithoutRetrying(CapturedOutput output) {
        MKK.always("/disclosureDetail/1093500", mkkError(401, "ER004", "Token gecersiz " + MARKER));

        RunReport report = job.run();

        assertThat(report.aborted()).isTrue();
        assertThat(report.abortReason()).contains("ER004").contains("401").doesNotContain(MARKER);
        assertThat(MKK.countRequests("/disclosureDetail/1093500")).isEqualTo(1);
        assertThat(count("dead_letter")).isZero();
        assertThat(cursors.find("THYAO")).isEmpty(); // resumable
        assertThat(output.getAll()).contains("ER004").doesNotContain(MARKER);
    }

    @Test
    void errorMessageTextNeverReachesTheDeadLetterTable() {
        MKK.always("/disclosureDetail/1101500", mkkError(400, "ER099", "Bilinmeyen hata " + MARKER));

        job.run();

        assertThat(status(1_101_500)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT error_message FROM dead_letter WHERE external_id = '1101500'")
                        .query(String.class)
                        .single())
                .isEqualTo("MKK request failed with status 400 (ER099)");
    }
}
