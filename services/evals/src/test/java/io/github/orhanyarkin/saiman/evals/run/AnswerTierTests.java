package io.github.orhanyarkin.saiman.evals.run;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.github.orhanyarkin.saiman.evals.golden.Kind;
import io.github.orhanyarkin.saiman.evals.report.AnswerReport;
import io.github.orhanyarkin.saiman.evals.report.AnswerReport.AnswerItem;
import io.github.orhanyarkin.saiman.evals.report.EvalReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tier A end to end against a stub seller and a stub ingest. */
@SpringBootTest(
        properties = {
            "saiman.evals.golden-set=classpath:golden/test-golden.yaml",
            "saiman.evals.answers.enabled=true",
            "saiman.evals.ingest.retry-attempts=1",
            "saiman.evals.seller.retry-attempts=2",
            "saiman.evals.seller.retry-wait=1ms"
        })
class AnswerTierTests {

    /** 40 characters of the token alphabet; with a trailing newline, as a configtree file would have it. */
    private static final String TOKEN = "tkn_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    private static final StubSeller SELLER = new StubSeller();
    private static final StubSeller OTHER_HOST = new StubSeller();
    private static final StubIngest INGEST = new StubIngest();

    @TempDir
    static Path out;

    @Autowired
    private EvalRunner runner;

    private ListAppender<ILoggingEvent> logs;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("saiman.evals.ingest.base-url", INGEST::baseUrl);
        registry.add("saiman.evals.seller.base-url", SELLER::baseUrl);
        registry.add("saiman.evals.seller.service-token", () -> TOKEN + "\n");
        registry.add("saiman.evals.output-dir", () -> out.toString());
    }

    @AfterAll
    static void stop() {
        SELLER.close();
        OTHER_HOST.close();
        INGEST.close();
    }

    @BeforeEach
    void stubs() {
        SELLER.reset(TOKEN);
        OTHER_HOST.reset(TOKEN);
        INGEST.reset();
        INGEST.answer("alfa sorusu", "kap:100:0000");
        INGEST.answer("beta sorusu", "kap:300:0000");
        INGEST.answer("ASELS son açıklamaları", "kap:12:0000");
        INGEST.answer("Ne zaman?", "kap:100:0000", "kap:555:0000");
        INGEST.answer("Net kar?", "kap:777:0000");
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    private static AnswerItem item(EvalReport report, String id) {
        return report.answers().items().stream()
                .filter(i -> i.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void assertTokenNowhere() throws IOException {
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            assertThat(text).doesNotContain(TOKEN);
        }
        for (String file : new String[] {"latest.md", "latest.json"}) {
            assertThat(Files.readString(out.resolve(file), StandardCharsets.UTF_8))
                    .doesNotContain(TOKEN);
        }
    }

    @Test
    void happyPathScoresAnswerAndRefusalAndSumsCost() throws IOException {
        SELLER.answer(
                "Ne zaman?",
                "ANSWERED",
                "Bildirim 18 Ekim 2023 tarihinde yayimlandi.",
                30_000,
                "kap:100:0000",
                "kap:555:0000");
        SELLER.answer("Net kar?", "REFUSED", null, 0);

        EvalReport report = runner.run();

        AnswerReport a = report.answers();
        assertThat(a.attempted()).isEqualTo(2);
        assertThat(a.outcomes()).containsEntry("ANSWERED", 1).containsEntry("REFUSED", 1);
        AnswerItem answered = item(report, "A-1");
        assertThat(answered.citations()).isEqualTo(2);
        assertThat(answered.validCitations()).isEqualTo(2);
        assertThat(answered.citationBasis()).isEqualTo("RETRIEVAL");
        assertThat(answered.citationRecall()).isEqualTo(1.0);
        assertThat(answered.factRecall()).isEqualTo(1.0);
        assertThat(answered.correct()).isTrue();
        assertThat(item(report, "U-1").correct()).isTrue();
        assertThat(a.totalCostUsdMicros()).isEqualTo(30_000);
        assertThat(a.meanCostUsdMicros()).isEqualTo(15_000);
        assertThat(a.summary().get(Kind.ANSWER)).containsEntry("taskSuccess", 1.0);
        assertThat(a.summary().get(Kind.UNANSWERABLE)).containsEntry("refusalCorrect", 1.0);
        assertThat(report.skipped()).isEmpty(); // scored here, so not "not scored in this tier"
        assertThat(SELLER.calls()).isEqualTo(2);
        assertThat(SELLER.requests().get(0)).contains("\"ticker\":\"THYAO\"").contains("Ne zaman?");

        String markdown = Files.readString(out.resolve("latest.md"), StandardCharsets.UTF_8);
        assertThat(markdown)
                .contains("## Tier A: answers")
                .contains("Date caveat")
                .contains("Total model cost: $0.030000")
                .contains("| ANSWER | 1 | 1 | taskSuccess | 1.000 |")
                .contains("| A-1 | THYAO | OK | ANSWERED | 2/2 | RETRIEVAL | 1.000 | 1.000 | none | true |");
        assertThat(Files.readString(out.resolve("latest.json"), StandardCharsets.UTF_8))
                .contains("\"totalCostUsdMicros\" : 30000");
        assertTokenNowhere();
    }

    @Test
    void aCitationOutsideTheRetrievedSetIsInvalidAndFailsTheTask() {
        SELLER.answer("Ne zaman?", "ANSWERED", "18.10.2023", 10_000, "kap:100:0000", "kap:999:0000");
        SELLER.answer("Net kar?", "ANSWERED", "kar 5 milyon", 5_000, "kap:777:0000");

        EvalReport report = runner.run();

        assertThat(item(report, "A-1").validCitations()).isEqualTo(1);
        assertThat(item(report, "A-1").correct()).isFalse();
        assertThat(item(report, "U-1").correct()).isFalse(); // answered although it should have refused
    }

    @Test
    void aWrongDateFailsTheFactCheck() {
        SELLER.answer("Ne zaman?", "ANSWERED", "19 Ekim 2023", 10_000, "kap:100:0000", "kap:555:0000");
        SELLER.answer("Net kar?", "NO_VALID_CITATIONS", "bilmiyorum", 1_000);

        EvalReport report = runner.run();

        assertThat(item(report, "A-1").factRecall()).isZero();
        assertThat(item(report, "A-1").correct()).isFalse();
        assertThat(item(report, "U-1").correct()).isTrue();
    }

    @Test
    void aRejectedTokenAbortsTheTierWithoutRetryAndWithoutLeakingTheToken() throws IOException {
        SELLER.expectedToken.set("some-other-token-value-that-is-long-enough-1");

        EvalReport report = runner.run();

        assertThat(SELLER.calls()).isEqualTo(1); // 401 is never retried and the second item is not tried
        assertThat(report.answers().abortReason())
                .contains("rejected the evals service token")
                .contains("401");
        assertThat(item(report, "A-1").status()).isEqualTo("CALL_FAILED:401");
        assertThat(item(report, "U-1").status()).isEqualTo("NOT_RUN");
        assertThat(report.errors()).isPositive(); // the CLI exits non-zero
        assertTokenNowhere();
    }

    @Test
    void theRunGuardLimitStopsTheTierAndIsNotAnOutcome() {
        SELLER.status("Ne zaman?", 429);

        EvalReport report = runner.run();

        assertThat(SELLER.calls()).isEqualTo(1);
        assertThat(report.answers().abortReason()).contains("429");
        assertThat(report.answers().outcomes()).isEmpty();
        assertThat(item(report, "U-1").status()).isEqualTo("NOT_RUN");
    }

    @Test
    void theDayCapStopsTheTierEarly() {
        SELLER.answer("Ne zaman?", "LLM_CAP", null, 0);
        SELLER.answer("Net kar?", "REFUSED", null, 0);

        EvalReport report = runner.run();

        assertThat(SELLER.calls()).isEqualTo(1);
        assertThat(report.answers().stoppedOnCap()).isTrue();
        assertThat(item(report, "A-1").outcome()).isEqualTo("LLM_CAP");
        assertThat(item(report, "A-1").correct()).isNull();
        assertThat(item(report, "U-1").status()).isEqualTo("NOT_RUN");
    }

    @Test
    void anErrorOutcomeIsRecordedAndTheTierContinues() {
        SELLER.answer("Ne zaman?", "ERROR", null, 0);
        SELLER.answer("Net kar?", "REFUSED", null, 0);

        EvalReport report = runner.run();

        assertThat(SELLER.calls()).isEqualTo(2);
        assertThat(item(report, "A-1").correct()).isNull();
        assertThat(item(report, "U-1").correct()).isTrue();
        assertThat(report.answers().errors()).isEqualTo(1);
        assertThat(report.errors()).isEqualTo(1);
    }

    @Test
    void aServerErrorIsRetriedButAClientErrorIsNot() {
        SELLER.status("Ne zaman?", 500);
        SELLER.status("Net kar?", 400);

        EvalReport report = runner.run();

        assertThat(SELLER.calls()).isEqualTo(3); // 2 attempts + 1
        assertThat(item(report, "A-1").status()).isEqualTo("CALL_FAILED:500");
        assertThat(item(report, "U-1").status()).isEqualTo("CALL_FAILED:400");
        assertThat(report.answers().abortReason()).isNull();
    }

    @Test
    void aRedirectToAnotherHostIsNeverFollowed() throws IOException {
        SELLER.redirect("Ne zaman?", OTHER_HOST.baseUrl() + "/internal/v1/eval/questions");
        SELLER.answer("Net kar?", "REFUSED", null, 0);

        EvalReport report = runner.run();

        assertThat(OTHER_HOST.calls()).isZero(); // the bearer token never reached it
        assertThat(item(report, "A-1").status()).isEqualTo("CALL_FAILED:302");
        assertTokenNowhere();
    }
}
