package io.github.orhanyarkin.saiman.apisecurity.testfixtures;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestTokensTests {

    @Test
    void literalDigestsAreTheSha256OfTheTokens() {
        assertThat(TestTokens.READER_SHA256).isEqualTo(TestTokens.digest(TestTokens.READER));
        assertThat(TestTokens.OPERATOR_SHA256).isEqualTo(TestTokens.digest(TestTokens.OPERATOR));
        assertThat(TestTokens.SERVICE_LEDGER_SHA256).isEqualTo(TestTokens.digest(TestTokens.SERVICE_LEDGER));
        assertThat(TestTokens.SERVICE_EVALS_SHA256).isEqualTo(TestTokens.digest(TestTokens.SERVICE_EVALS));
    }

    @Test
    void tokensAreWellFormedAndDistinct() {
        assertThat(java.util.List.of(
                        TestTokens.READER,
                        TestTokens.OPERATOR,
                        TestTokens.SERVICE_LEDGER,
                        TestTokens.SERVICE_EVALS,
                        TestTokens.UNKNOWN))
                .allMatch(t -> t.matches("^[A-Za-z0-9_-]{32,128}$"))
                .doesNotHaveDuplicates();
    }
}
