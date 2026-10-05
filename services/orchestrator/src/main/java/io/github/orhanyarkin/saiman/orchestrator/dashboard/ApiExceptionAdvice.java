package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
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

    private static final String INTERNAL_ERROR = "internal error";
    private static final URI UNMATCHED = URI.create("/unmatched");

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionAdvice.class);

    /** A path variable or request parameter that does not convert: names the declared parameter only. */
    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex instanceof MethodArgumentTypeMismatchException m
                        && !m.getName().isBlank()
                ? "`" + m.getName() + "` is invalid" // the declared method parameter, not request-derived
                : "a parameter is invalid";
        return handleExceptionInternal(
                ex, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), headers, status, request);
    }

    @ExceptionHandler(ReadTimeoutException.class)
    @Nullable
    ResponseEntity<Object> readTimedOut(ReadTimeoutException ex, WebRequest request) {
        return unavailable(ex, "the read took too long; retry", request);
    }

    @ExceptionHandler(ReadSaturatedException.class)
    @Nullable
    ResponseEntity<Object> readSaturated(ReadSaturatedException ex, WebRequest request) {
        return unavailable(ex, "too many reads in flight; retry", request);
    }

    /** Anything unforeseen: a fixed 500. Only the exception class is logged, never its message. */
    @ExceptionHandler(Exception.class)
    @Nullable
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        LOG.error("Unhandled exception: {}", ex.getClass().getName());
        return handleExceptionInternal(
                ex,
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR),
                new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                request);
    }

    private @Nullable ResponseEntity<Object> unavailable(Exception ex, String detail, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return handleExceptionInternal(
                ex,
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail),
                headers,
                HttpStatus.SERVICE_UNAVAILABLE,
                request);
    }

    /**
     * Every problem this advice produces ends here: {@code instance} becomes the route template (or a
     * constant), and {@code detail} a fixed text unless the exception's own text is already fixed (our
     * handlers above, and {@link ErrorResponseException}s the controllers throw with constant details).
     * Spring's texts quote request input (the {@code Content-Type}, a value that failed to convert).
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setInstance(routeTemplate(request));
            if (!hasFixedDetail(ex)) {
                problem.setDetail(fixedDetail(ex, response.getStatusCode()));
            }
        }
        return response;
    }

    private static boolean hasFixedDetail(Exception ex) {
        if (ex instanceof ReadTimeoutException || ex instanceof ReadSaturatedException) {
            return true;
        }
        if (ex instanceof TypeMismatchException) {
            return !(ex instanceof ConversionNotSupportedException); // handleTypeMismatch composed the text
        }
        return ex instanceof ErrorResponseException && !(ex instanceof ResponseStatusException);
    }

    private static String fixedDetail(Exception ex, HttpStatusCode status) {
        if (ex instanceof NoResourceFoundException) {
            return "resource not found";
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return "method not supported";
        }
        if (status.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return INTERNAL_ERROR;
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "request failed" : known.getReasonPhrase().toLowerCase(Locale.ROOT);
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
