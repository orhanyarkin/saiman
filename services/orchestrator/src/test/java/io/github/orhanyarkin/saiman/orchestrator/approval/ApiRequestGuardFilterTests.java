package io.github.orhanyarkin.saiman.orchestrator.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Filter logic in isolation. The servlet path is set the way Tomcat sets it for a DispatcherServlet
 * mapped to {@code /}; {@link ApiRequestGuardHttpTests} covers what a real Tomcat does with crafted
 * request lines.
 */
class ApiRequestGuardFilterTests {

    private static final String URI = "/api/v1/runs/r/approvals/a";

    private final ApiRequestGuardFilter filter = new ApiRequestGuardFilter(
            new ApiGuardProperties(List.of("localhost", "127.0.0.1", "[::1]", "orchestrator")));

    private record Result(int status, boolean reachedController) {}

    private Result call(
            String method, String uri, @Nullable String host, @Nullable String contentType, @Nullable String csrf)
            throws Exception {
        return call(method, uri, uri, host, contentType, csrf);
    }

    private Result call(
            String method,
            String rawUri,
            String servletPath,
            @Nullable String host,
            @Nullable String contentType,
            @Nullable String csrf)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, rawUri);
        request.setServletPath(servletPath);
        if (host != null) {
            request.addHeader("Host", host);
        }
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (csrf != null) {
            request.addHeader(ApiRequestGuardFilter.CSRF_HEADER, csrf);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response.getStatus(), chain.getRequest() != null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:8080", "127.0.0.1:1", "[::1]:8080", "orchestrator:8080", "ORCHESTRATOR"})
    void allowedHostsWithJsonAndCsrfPass(String host) throws Exception {
        assertThat(call("POST", URI, host, "application/json", "1")).isEqualTo(new Result(200, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "localhost.evil.example", "orchestrator.evil:8080", " "})
    void otherHostsGet400(String host) throws Exception {
        assertThat(call("POST", URI, host, "application/json", "1")).isEqualTo(new Result(400, false));
        assertThat(call("GET", URI, host, null, null)).isEqualTo(new Result(400, false));
    }

    @Test
    void writesNeedBothJsonAndTheCsrfHeader() throws Exception {
        assertThat(call("POST", URI, "localhost", "application/json", null)).isEqualTo(new Result(403, false));
        assertThat(call("POST", URI, "localhost", "text/plain", "1")).isEqualTo(new Result(403, false));
        assertThat(call("POST", URI, "localhost", "application/x-www-form-urlencoded", "1"))
                .isEqualTo(new Result(403, false));
        assertThat(call("POST", URI, "localhost", "application/json", "0")).isEqualTo(new Result(403, false));
        assertThat(call("DELETE", URI, "localhost", null, "1")).isEqualTo(new Result(403, false));
    }

    @Test
    void readsNeedNoCsrfHeader() throws Exception {
        assertThat(call("GET", URI, "localhost", null, null)).isEqualTo(new Result(200, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/actuator/info", "/anything/else", "/error"})
    void everyPathNeedsAnAllowedHostAndEveryWriteNeedsTheCsrfHeader(String path) throws Exception {
        assertThat(call("GET", path, "evil.example", null, null)).isEqualTo(new Result(400, false));
        assertThat(call("GET", path, null, null, null)).isEqualTo(new Result(400, false));
        assertThat(call("POST", path, "localhost", "text/plain", null)).isEqualTo(new Result(403, false));
        assertThat(call("GET", path, "localhost", null, null)).isEqualTo(new Result(200, true));
    }

    @Test
    void onlyHealthReadsAreExemptFromTheHostCheck() throws Exception {
        assertThat(call("GET", "/actuator/health", "evil.example", null, null)).isEqualTo(new Result(200, true));
        assertThat(call("GET", "/actuator/health/liveness", null, null, null)).isEqualTo(new Result(200, true));
        assertThat(call("POST", "/actuator/health", "evil.example", "application/json", "1"))
                .isEqualTo(new Result(400, false));
        assertThat(call("GET", "/actuator/healthz", "evil.example", null, null)).isEqualTo(new Result(400, false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api;x=1/v1/runs/r/approvals/a",
                "/api/v1;/runs",
                "/%61pi/v1/runs/r/approvals/a",
                "/api%2Fv1/runs",
                "//api/v1/runs/r/approvals/a",
                "/api/v1//runs",
                "/api\\v1/runs",
                "/actuator/health;x"
            })
    void aNonCanonicalRawPathIsRefusedEvenWithGoodHeaders(String rawUri) throws Exception {
        assertThat(call("POST", rawUri, "/api/v1/runs/r/approvals/a", "localhost", "application/json", "1"))
                .isEqualTo(new Result(400, false));
        assertThat(call("GET", rawUri, "/api/v1/runs", "localhost", null, null)).isEqualTo(new Result(400, false));
    }

    @Test
    void aRawPathThatTheContainerNormalisedDifferentlyIsRefused() throws Exception {
        // Tomcat resolves dot segments: the raw URI and the servlet path then differ.
        assertThat(call("GET", "/x/../api/v1/runs", "/api/v1/runs", "localhost", null, null))
                .isEqualTo(new Result(400, false));
        assertThat(call("GET", "/api/./v1/runs", "/api/v1/runs", "localhost", null, null))
                .isEqualTo(new Result(400, false));
    }
}
