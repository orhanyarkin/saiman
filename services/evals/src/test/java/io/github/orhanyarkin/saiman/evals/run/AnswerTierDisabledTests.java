package io.github.orhanyarkin.saiman.evals.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evals.report.EvalReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Tier A off (the default): no seller object exists, no call is made, the report says so. */
@SpringBootTest(properties = "saiman.evals.golden-set=classpath:golden/test-golden.yaml")
class AnswerTierDisabledTests {

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
        registry.add("saiman.evals.output-dir", () -> out.toString());
    }

    @AfterAll
    static void stop() {
        SELLER.close();
        INGEST.close();
    }

    @Test
    void makesNoSellerCallAndReportsTierANotRun() throws IOException {
        INGEST.answer("alfa sorusu", "kap:100:0000");
        INGEST.answer("beta sorusu", "kap:300:0000");
        INGEST.answer("ASELS son açıklamaları", "kap:12:0000");

        EvalReport report = runner.run();

        assertThat(SELLER.calls()).isZero();
        assertThat(report.answers()).isNull();
        assertThat(Files.readString(out.resolve("latest.md"), StandardCharsets.UTF_8))
                .contains("Tier A not run")
                .contains("## Not scored in this tier");
        assertThat(Files.readString(out.resolve("latest.json"), StandardCharsets.UTF_8))
                .doesNotContain("\"answers\"");
    }
}
