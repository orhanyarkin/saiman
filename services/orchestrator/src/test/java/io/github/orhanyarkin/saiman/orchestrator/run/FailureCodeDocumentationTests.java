package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every failure code a client can receive is documented in the event schema. */
class FailureCodeDocumentationTests {

    /** The test runs with the module directory as working directory. */
    private static final Path SCHEMA = Path.of("../../docs/events/agent.run-step.v1.md");

    @ParameterizedTest
    @EnumSource(FailureCode.class)
    void everyFailureCodeIsInTheRunFailedRowOfTheSchema(FailureCode code) throws IOException {
        String runFailedRow = Files.readAllLines(SCHEMA).stream()
                .filter(line -> line.startsWith("| RUN_FAILED |"))
                .findFirst()
                .orElseThrow();

        assertThat(runFailedRow).containsPattern("\\b" + code.name() + "\\b");
    }
}
