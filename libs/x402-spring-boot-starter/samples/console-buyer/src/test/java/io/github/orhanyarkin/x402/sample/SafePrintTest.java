package io.github.orhanyarkin.x402.sample;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafePrintTest {

    @Test
    void nullBecomesEmptyString() {
        assertThat(SafePrint.of(null)).isEmpty();
    }

    @Test
    void ordinaryTextPassesThroughUnchanged() {
        assertThat(SafePrint.of("insufficient_funds")).isEqualTo("insufficient_funds");
    }

    @Test
    void controlCharactersAreStripped() {
        String withControlChars = "line1\r\nline2\u0007\u001b[31mred\u001b[0m";
        assertThat(SafePrint.of(withControlChars)).doesNotContainPattern("[\\x00-\\x1f\\x7f]");
    }

    @Test
    void longValuesAreCappedWithATruncationMarker() {
        String tooLong = "x".repeat(500);

        String result = SafePrint.of(tooLong);

        assertThat(result).hasSizeLessThan(500).endsWith("...");
    }
}
