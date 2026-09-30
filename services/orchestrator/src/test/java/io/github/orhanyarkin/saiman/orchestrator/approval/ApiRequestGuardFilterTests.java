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

class ApiRequestGuardFilterTests {

    private static final String URI = "/api/v1/runs/r/approvals/a";

    private final ApiRequestGuardFilter filter = new ApiRequestGuardFilter(
            new ApiGuardProperties(List.of("localhost", "127.0.0.1", "[::1]", "orchestrator")));

    private record Result(int status, boolean reachedController) {}

    private Result call(
            String method, String uri, @Nullable String host, @Nullable String contentType, @Nullable String csrf)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
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
    void readsAndNonApiPathsNeedNoHeader() throws Exception {
        assertThat(call("GET", URI, "localhost", null, null)).isEqualTo(new Result(200, true));
        assertThat(call("POST", "/actuator/health", "evil.example", "text/plain", null))
                .isEqualTo(new Result(200, true));
    }
}
