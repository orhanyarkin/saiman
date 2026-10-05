package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import java.net.URI;
import java.net.URISyntaxException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Problem Details for the API with fixed texts only. Spring's defaults put the raw input into
 * {@code detail} ("Failed to convert 'id' with value: '...'") and the request path into
 * {@code instance}; on an unauthenticated service that is reflected attacker text. Here {@code
 * detail} names the parameter (never its value) and {@code instance} is the matched route template.
 *
 * <p>Registering a {@link ResponseEntityExceptionHandler} bean replaces Boot's own one.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionAdvice extends ResponseEntityExceptionHandler {

    private static final URI UNMATCHED = URI.create("/unmatched");

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        String detail = (name == null || name.isBlank() ? "a parameter" : "`" + name + "`") + " is invalid";
        return handleExceptionInternal(
                ex, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ex.getBody().setDetail("resource not found");
        return super.handleNoResourceFoundException(ex, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ex.getBody().setDetail("method not supported");
        return super.handleHttpRequestMethodNotSupported(ex, headers, status, request);
    }

    @ExceptionHandler(ReadTimeoutException.class)
    @Nullable
    ResponseEntity<Object> readTimedOut(ReadTimeoutException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return handleExceptionInternal(
                ex,
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "the read took too long; retry"),
                headers,
                HttpStatus.SERVICE_UNAVAILABLE,
                request);
    }

    /** Sets {@code instance} to the route template (or a constant) before the parent would use the raw path. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail given
                ? given
                : body == null && ex instanceof ErrorResponse error ? error.getBody() : null;
        if (problem != null) {
            problem.setInstance(routeTemplate(request));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static URI routeTemplate(WebRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        if (pattern instanceof String route && route.startsWith("/")) {
            try {
                return new URI(null, null, route, null); // quotes the braces of "{runId}"
            } catch (URISyntaxException e) {
                return UNMATCHED;
            }
        }
        return UNMATCHED;
    }
}
