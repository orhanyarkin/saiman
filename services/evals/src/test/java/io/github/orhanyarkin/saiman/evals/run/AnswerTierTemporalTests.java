package io.github.orhanyarkin.saiman.evals.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evals.golden.Kind;
import io.github.orhanyarkin.saiman.evals.report.AnswerReport.AnswerItem;
import io.github.orhanyarkin.saiman.evals.report.EvalReport;
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

/** TEMPORAL items: the relative-time check, with the answer text kept out of the report. */
@SpringBootTest(
        properties = {
            "saiman.evals.golden-set=classpath:golden/test-golden-temporal.yaml",
            "saiman.evals.answers.enabled=true",
            "saiman.evals.ingest.retry-attempts=1"
        })
class AnswerTierTemporalTests {

    private static final String TOKEN = "tkn_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
    private static final String SISE_SENTENCE = "SISE icin son 7 günde yeni özel durum bulunmamaktadır.";

    private static final StubSeller SELLER = new StubSeller();
    private static final StubIngest INGEST = new StubIngest();

    @TempDir
    static Path out;

    @Autowired
    private EvalRunner runner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("saiman.evals.ingest.base-url", INGEST::baseUrl);
        registry.add("saiman.evals.seller.base-url", SELLER::baseUrl);
        registry.add("saiman.evals.seller.service-token", () -> TOKEN);
        registry.add("saiman.evals.output-dir", () -> out.toString());
    }

    @AfterAll
    static void stop() {
        SELLER.close();
        INGEST.close();
    }

    @BeforeEach
    void stubs() {
        SELLER.reset(TOKEN);
        INGEST.reset();
        INGEST.answer("Güncel neler var?", "kap:11:0000");
        INGEST.answer("En son neler var?", "kap:12:0000");
        INGEST.answer("Yeni bir şey var mı?", "kap:13:0000");
        INGEST.answer("Ne zaman?", "kap:100:0000", "kap:200:0000");
    }

    private static AnswerItem item(EvalReport report, String id) {
        return report.answers().items().stream()
                .filter(i -> i.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void flagsRelativeTimeScoresCleanAnswersAndNeverPrintsTheAnswerText() throws IOException {
        SELLER.answer("Güncel neler var?", "ANSWERED", SISE_SENTENCE, 1_000, "kap:11:0000");
        SELLER.answer("En son neler var?", "ANSWERED", "Son bildirim 29.12.2023 tarihlidir.", 1_000, "kap:12:0000");
        SELLER.answer("Yeni bir şey var mı?", "REFUSED", null, 0);
        SELLER.answer(
                "Ne zaman?",
                "ANSWERED",
                "18.10.2023 ve 18.05.2023, bugün değil.",
                1_000,
                "kap:100:0000",
                "kap:200:0000");

        EvalReport report = runner.run();

        AnswerItem bad = item(report, "T-1");
        assertThat(bad.relativeTimeFree()).isFalse();
        assertThat(bad.relativeTimeHits()).containsExactly("son-N-gun/hafta/ay");
        assertThat(bad.correct()).isFalse();
        AnswerItem good = item(report, "T-2");
        assertThat(good.relativeTimeFree()).isTrue();
        assertThat(good.correct()).isTrue();
        AnswerItem refused = item(report, "T-3");
        assertThat(refused.correct()).isNull(); // reported, not a violation
        assertThat(refused.relativeTimeFree()).isNull();
        // the extra metric on ANSWER items
        assertThat(item(report, "A-1").relativeTimeFree()).isFalse();
        assertThat(report.answers().summary().get(Kind.TEMPORAL))
                .containsEntry("temporalSuccess", 0.5)
                .containsEntry("relativeTimeFree", 0.5);
        assertThat(report.answers().summary().get(Kind.ANSWER)).containsEntry("relativeTimeFree", 0.0);

        String markdown = Files.readString(out.resolve("latest.md"), StandardCharsets.UTF_8);
        String json = Files.readString(out.resolve("latest.json"), StandardCharsets.UTF_8);
        assertThat(markdown)
                .contains("| T-1 | SISE | OK | ANSWERED | 1/1 | RETRIEVAL | - | - | son-N-gun/hafta/ay | false |");
        assertThat(markdown).contains("temporalSuccess");
        assertThat(markdown + json).doesNotContain("bulunmamaktadır").doesNotContain("bugün değil");
    }
}
