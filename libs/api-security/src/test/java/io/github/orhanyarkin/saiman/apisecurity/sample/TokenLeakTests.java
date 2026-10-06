package io.github.orhanyarkin.saiman.apisecurity.sample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Planted tokens (well formed but unknown, malformed, oversized, and a valid one) never appear in the logs, even with
 * Spring Security and the library at TRACE, nor in the response.
 */
@SpringBootTest(
        classes = SampleApplication.class,
        properties = {
            TestTokens.READER_PROPERTY,
            TestTokens.OPERATOR_PROPERTY,
            "logging.level.org.springframework.security=TRACE",
            "logging.level.org.springframework.web=TRACE",
            "logging.level.io.github.orhanyarkin=TRACE"
        })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TokenLeakTests {

    private static final String PLANTED_UNKNOWN = "plantedSecretTokenUnknown_0123456789abcdefXYZ";
    private static final String PLANTED_MALFORMED = "plantedSecretTokenMalformed.0123456789abcdefXYZ";
    private static final String PLANTED_OVERSIZED = "plantedSecretTokenOversized" + "x".repeat(200);

    @Autowired
    MockMvcTester mvc;

    @Test
    void headerTokensNeverReachLogsOrResponses(CapturedOutput output) throws Exception {
        for (String token : List.of(PLANTED_UNKNOWN, PLANTED_MALFORMED, PLANTED_OVERSIZED, TestTokens.OPERATOR)) {
            for (String uri : List.of("/api/v1/runs", "/api/v2/unmapped-by-rules")) {
                MvcTestResult result = mvc.get()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                        .exchange();
                assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
                assertThat(result.getResponse().getHeaderNames().stream()
                                .flatMap(name -> result.getResponse().getHeaders(name).stream()))
                        .noneMatch(value -> value.contains(token));
                assertThat(result.getMvcResult().getResolvedException()).isNull();
            }
        }
        // The capture covered real TRACE output (otherwise "not in the log" proves nothing).
        assertThat(output.getAll()).contains("Securing GET /api/v1/runs");
        assertThat(output.getAll())
                .doesNotContain(PLANTED_UNKNOWN)
                .doesNotContain(PLANTED_MALFORMED)
                .doesNotContain("plantedSecretTokenOversized")
                .doesNotContain(TestTokens.OPERATOR);
    }

    /**
     * Pins a known limit rather than a guarantee: a token sent as {@code ?access_token=} is ignored (401), but Spring
     * Security's own DEBUG/TRACE lines log the request URL with its query string, and so does any access log. This
     * library cannot prevent that; clients must never put a token in a URL (ADR-0023), and production runs at INFO.
     */
    @Test
    void queryParameterTokenIsIgnoredButSpringSecurityDebugLogsTheUrl(CapturedOutput output) {
        String planted = "plantedQueryToken_0123456789abcdefghijXYZ";
        assertThat(mvc.get()
                        .uri("/api/v1/runs")
                        .queryParam("access_token", planted)
                        .exchange())
                .hasStatus(401);
        assertThat(output.getAll()).contains("Securing GET /api/v1/runs?access_token=" + planted);
    }
}
