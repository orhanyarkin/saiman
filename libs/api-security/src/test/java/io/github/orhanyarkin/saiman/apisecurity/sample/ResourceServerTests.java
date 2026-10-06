package io.github.orhanyarkin.saiman.apisecurity.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.ProblemAccessDeniedHandler;
import io.github.orhanyarkin.saiman.apisecurity.ProblemAuthenticationEntryPoint;
import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The mechanism applied by {@code SaimanResourceServer} with a sample service's rules, through MockMvc. */
@SpringBootTest(
        classes = SampleApplication.class,
        properties = {
            TestTokens.READER_PROPERTY,
            TestTokens.OPERATOR_PROPERTY,
            TestTokens.SERVICE_LEDGER_PROPERTY,
            TestTokens.SERVICE_EVALS_PROPERTY
        })
@AutoConfigureMockMvc
class ResourceServerTests {

    @Autowired
    MockMvcTester mvc;

    private static void assertUnauthorized(MvcTestResult result) {
        assertThat(result)
                .hasStatus(401)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyText()
                .isEqualTo(ProblemAuthenticationEntryPoint.BODY);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    private static void assertForbidden(MvcTestResult result) {
        assertThat(result)
                .hasStatus(403)
                .doesNotContainHeader(HttpHeaders.WWW_AUTHENTICATE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyText()
                .isEqualTo(ProblemAccessDeniedHandler.BODY);
    }

    @Test
    void missingTokenIs401WithBearerChallengeAndFixedBody() {
        assertUnauthorized(mvc.get().uri("/api/v1/runs").exchange());
        assertUnauthorized(mvc.post().uri("/api/v1/runs").exchange());
    }

    @Test
    void unknownMalformedOrNonBearerTokensGetTheSame401() {
        for (String authorization : new String[] {
            TestTokens.bearer(TestTokens.UNKNOWN), // well formed, no role
            TestTokens.bearer("too-short"),
            TestTokens.bearer("a".repeat(129)),
            TestTokens.bearer("saiman.test.token.with.dots.0123456789"), // rejected by the introspector's pattern
            "Bearer ", // empty
            "Bearer two words", // rejected by Spring's header parser
            "Basic " + TestTokens.READER, // wrong scheme: treated as no token
            TestTokens.READER // no scheme
        }) {
            assertUnauthorized(mvc.get()
                    .uri("/api/v1/runs")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchange());
        }
    }

    @Test
    void tokenInAQueryOrFormParameterIsIgnored() {
        assertUnauthorized(mvc.get()
                .uri("/api/v1/runs")
                .queryParam("access_token", TestTokens.OPERATOR)
                .exchange());
        assertUnauthorized(mvc.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("access_token=" + TestTokens.OPERATOR)
                .exchange());
    }

    @Test
    void readerCanReadButNotStartARun() {
        assertThat(mvc.get()
                        .uri("/api/v1/runs")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.READER))
                        .exchange())
                .hasStatusOk()
                .bodyText()
                .isEqualTo("runs for reader:bb8a7703");
        assertForbidden(mvc.post()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.READER))
                .exchange());
    }

    @Test
    void operatorCanDoReaderThingsAndOperatorThings() {
        assertThat(mvc.get()
                        .uri("/api/v1/runs")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.OPERATOR))
                        .exchange())
                .hasStatusOk()
                .bodyText()
                .isEqualTo("runs for operator:064f3fe6");
        assertThat(mvc.post()
                        .uri("/api/v1/runs")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.OPERATOR))
                        .exchange())
                .hasStatusOk()
                .bodyText()
                .isEqualTo("started by operator:064f3fe6");
    }

    @Test
    void serviceRoutesNeedTheRightCaller() {
        assertThat(mvc.get()
                        .uri("/internal/credit-notes/latest")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.SERVICE_LEDGER))
                        .exchange())
                .hasStatusOk()
                .bodyText()
                .isEqualTo("credit note for service-ledger:84c219d5");
        for (String token : new String[] {TestTokens.SERVICE_EVALS, TestTokens.OPERATOR}) {
            assertForbidden(mvc.get()
                    .uri("/internal/credit-notes/latest")
                    .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                    .exchange());
        }
        // A service token is not a human role.
        assertForbidden(mvc.get()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.SERVICE_LEDGER))
                .exchange());
    }

    @Test
    void routesOutsideTheRulesAreDeniedEvenForAnOperator() {
        assertForbidden(mvc.get()
                .uri("/api/v2/unmapped-by-rules")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.OPERATOR))
                .exchange());
        assertUnauthorized(mvc.get().uri("/api/v2/unmapped-by-rules").exchange());
    }

    @Test
    void noSessionIsCreated() {
        MvcTestResult result = mvc.get()
                .uri("/api/v1/runs")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.READER))
                .exchange();
        assertThat(result).hasStatusOk();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }
}
