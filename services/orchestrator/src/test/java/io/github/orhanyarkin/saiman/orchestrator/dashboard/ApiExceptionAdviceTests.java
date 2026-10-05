package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

/** Every problem the advice produces is fixed text: the request's own words never come back. */
class ApiExceptionAdviceTests {

    private static final String MARKER = "ZZMARK";

    private final ApiExceptionAdvice advice = new ApiExceptionAdvice();

    private ServletWebRequest request() {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/api/v1/runs/" + MARKER);
        raw.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/runs/{runId}");
        raw.addHeader("Content-Type", "application/json;x=\"" + MARKER + "\"");
        return new ServletWebRequest(raw);
    }

    private ProblemDetail assertNothingEchoed(ResponseEntity<Object> response) {
        assertThat(response).isNotNull();
        assertThat(response.getHeaders().toString()).doesNotContain(MARKER);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.toString()).doesNotContain(MARKER).doesNotContain("SELECT");
        assertThat(problem.getInstance()).hasToString("/api/v1/runs/%7BrunId%7D");
        return problem;
    }

    @Test
    void anUnsupportedMediaTypeDoesNotQuoteTheContentType() throws Exception {
        ResponseEntity<Object> response = advice.handleException(
                new HttpMediaTypeNotSupportedException(
                        MediaType.parseMediaType("application/x-" + MARKER), List.of(MediaType.APPLICATION_JSON)),
                request());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(assertNothingEchoed(response).getDetail()).isEqualTo("unsupported media type");
    }

    @Test
    void aConversionFailureDoesNotQuoteTheValue() throws Exception {
        ResponseEntity<Object> response = advice.handleException(
                new ConversionNotSupportedException(MARKER, String.class, new IllegalStateException(MARKER)),
                request());
        assertThat(assertNothingEchoed(response).getDetail()).isEqualTo("internal error");
    }

    @Test
    void aTypeMismatchNamesOnlyTheDeclaredMethodParameter() throws Exception {
        ResponseEntity<Object> declared = advice.handleException(
                new MethodArgumentTypeMismatchException(MARKER, UUID.class, "runId", null, null), request());
        assertThat(assertNothingEchoed(declared).getDetail()).isEqualTo("`runId` is invalid");

        TypeMismatchException other = new TypeMismatchException(MARKER, Integer.class);
        other.initPropertyName(MARKER);
        ResponseEntity<Object> bound = advice.handleException(other, request());
        assertThat(assertNothingEchoed(bound).getDetail()).isEqualTo("a parameter is invalid");
    }

    @Test
    void anUnexpectedExceptionIsAFixedInternalErrorWithNoPathOrSql() {
        ResponseEntity<Object> response = advice.unexpected(
                new DataAccessResourceFailureException("SELECT secret FROM run WHERE id = '" + MARKER + "'"),
                request());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(assertNothingEchoed(response).getDetail()).isEqualTo("internal error");
    }

    @Test
    void theSaturationProblemIsAFixed503WithRetryAfter() {
        ResponseEntity<Object> response = advice.readSaturated(new ReadSaturatedException(), request());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(assertNothingEchoed(response).getDetail()).isEqualTo("too many reads in flight; retry");
    }
}
