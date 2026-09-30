package io.github.orhanyarkin.saiman.ingest.guard;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalRequestGuardFilterTests {

    private final InternalRequestGuardFilter filter = new InternalRequestGuardFilter(new InternalGuardProperties(
            List.of("localhost", "127.0.0.1", "[::1]", "ingest", "ingest:8083", "other:9000")));

    private record Result(int status, boolean reachedController) {}

    private Result call(String method, String uri, String host, String contentType, String internalHeader)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        if (host != null) {
            request.addHeader("Host", host);
        }
        if (contentType != null) {
            request.setContentType(contentType);
        }
        if (internalHeader != null) {
            request.addHeader("X-Saiman-Internal", internalHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response.getStatus(), chain.getRequest() != null);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "localhost:54321",
                "127.0.0.1:8083",
                "[::1]:8083",
                "ingest",
                "ingest:8083",
                "LOCALHOST:1",
                "other:9000"
            })
    void allowedHostsPass(String host) throws Exception {
        assertThat(call("GET", "/internal/v1/tickers", host, null, null)).isEqualTo(new Result(200, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "evil.example:8083", "localhost.evil.example", "other:1", " "})
    void otherHostsGet400(String host) throws Exception {
        assertThat(call("GET", "/internal/v1/tickers", host, null, null)).isEqualTo(new Result(400, false));
    }

    @Test
    void missingHostGets400() throws Exception {
        assertThat(call("GET", "/internal/v1/tickers", null, null, null)).isEqualTo(new Result(400, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void stateChangingWithoutJsonOrHeaderGets403(String method) throws Exception {
        String uri = "/internal/v1/admin/retry-dlq";
        assertThat(call(method, uri, "localhost:1", null, null)).isEqualTo(new Result(403, false));
        assertThat(call(method, uri, "localhost:1", "text/plain", null)).isEqualTo(new Result(403, false));
        assertThat(call(method, uri, "localhost:1", "application/x-www-form-urlencoded", null))
                .isEqualTo(new Result(403, false));
        assertThat(call(method, uri, "localhost:1", "text/plain", "0")).isEqualTo(new Result(403, false));
    }

    @Test
    void stateChangingWithJsonPasses() throws Exception {
        assertThat(call("POST", "/internal/v1/retrieve", "ingest:8083", "application/json", null))
                .isEqualTo(new Result(200, true));
        assertThat(call("POST", "/internal/v1/retrieve", "ingest:8083", "application/json;charset=UTF-8", null))
                .isEqualTo(new Result(200, true));
    }

    @Test
    void stateChangingWithTheCustomHeaderPasses() throws Exception {
        assertThat(call("POST", "/internal/v1/admin/retry-dlq", "localhost:1", null, "1"))
                .isEqualTo(new Result(200, true));
    }

    @Test
    void getsNeedNoHeader() throws Exception {
        assertThat(call("GET", "/internal/v1/tickers", "localhost:1", null, null))
                .isEqualTo(new Result(200, true));
    }

    @Test
    void healthProbesSkipOnlyTheHostCheck() throws Exception {
        assertThat(call("GET", "/actuator/health", "evil.example", null, null)).isEqualTo(new Result(200, true));
        assertThat(call("GET", "/actuator/health/liveness", "evil.example", null, null))
                .isEqualTo(new Result(200, true));
        assertThat(call("GET", "/actuator/info", "evil.example", null, null)).isEqualTo(new Result(400, false));
        assertThat(call("POST", "/actuator/health", "evil.example", "text/plain", null))
                .isEqualTo(new Result(403, false));
    }

    @Test
    void theCsrfRuleAppliesToEveryPath() throws Exception {
        assertThat(call("POST", "/anything/else", "localhost:1", "text/plain", null))
                .isEqualTo(new Result(403, false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal;x=1/v1/admin/retry-dlq",
                "/%69nternal/v1/admin/retry-dlq",
                "//internal/v1/admin/retry-dlq",
                "/internal%2Fv1/admin/retry-dlq",
                "/internal/../internal/v1/admin/retry-dlq",
                "/internal/./v1/admin/retry-dlq",
                "/internal\\v1"
            })
    void nonNormalRawPathsGet400(String uri) throws Exception {
        assertThat(call("POST", uri, "localhost:1", "application/json", null)).isEqualTo(new Result(400, false));
    }
}
