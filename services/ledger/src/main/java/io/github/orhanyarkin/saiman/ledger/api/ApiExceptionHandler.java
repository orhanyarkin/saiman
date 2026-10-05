package io.github.orhanyarkin.saiman.ledger.api;

import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The service's one Problem Details (RFC 9457) handler for Spring MVC's own exceptions. It replaces Boot's
 * {@code ProblemDetailsExceptionHandler}, which backs off when a {@link ResponseEntityExceptionHandler} bean exists,
 * because the defaults echo the request: a type-mismatch {@code detail} quotes the raw value ("Failed to convert 'id'
 * with value: '...'"), other details quote headers or the path, and {@code instance} defaults to the request URI.
 * Everything that passes through here therefore gets:
 *
 * <ul>
 *   <li>a fixed {@code detail}: {@code "<parameter> is invalid"} or {@code "<parameter> is required"} with the
 *       parameter's declared name (never its value), the text of an {@link ApiProblems} exception (fixed strings by
 *       construction), or the status' reason phrase for anything else;
 *   <li>an {@code instance} that is the matched route template ({@code /api/v1/ledger/payments/{paymentId}},
 *       percent-encoded braces) or {@code /} when no handler matched, never the request URI.
 * </ul>
 *
 * Besides Spring MVC's exceptions it renders a dashboard read that timed out or found the bulkhead full
 * ({@link BoundedReads}) as a fixed 503 with {@code Retry-After}, and anything else (a {@code DataAccessException}
 * whose message quotes SQL, a bug) as a fixed 500 "internal error", logging only the exception class.
 *
 * Hidden from springdoc so it does not add responses to every operation of the contract.
 */
@Hidden
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Seconds a client should wait after a 503 from a dashboard read. */
    static final String READ_RETRY_AFTER_SECONDS = "5";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BoundedReads.ReadTimeoutException.class)
    @Nullable
    ResponseEntity<Object> readTimedOut(WebRequest request) {
        return unavailable("The read took too long; retry later", request);
    }

    @ExceptionHandler(BoundedReads.ReadsBusyException.class)
    @Nullable
    ResponseEntity<Object> readsBusy(WebRequest request) {
        return unavailable("Too many concurrent reads; retry later", request);
    }

    /**
     * Last resort: a fixed 500 whose body carries neither the exception message (a {@code DataAccessException} quotes
     * its SQL) nor the request path. Spring picks the most specific handler, so MVC's own exceptions and the ones above
     * never land here.
     */
    @ExceptionHandler(Exception.class)
    @Nullable
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        log.error("Unhandled {} on {}", ex.getClass().getName(), route(request));
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
        return handleExceptionInternal(
                problemException(HttpStatus.INTERNAL_SERVER_ERROR, body),
                body,
                new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                request);
    }

    private @Nullable ResponseEntity<Object> unavailable(String detail, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, READ_RETRY_AFTER_SECONDS);
        return handleExceptionInternal(
                problemException(HttpStatus.SERVICE_UNAVAILABLE, body),
                body,
                headers,
                HttpStatus.SERVICE_UNAVAILABLE,
                request);
    }

    /** Wraps a fixed problem so {@link #fixedDetail} keeps its detail (it trusts plain ErrorResponseExceptions). */
    private static ErrorResponseException problemException(HttpStatus status, ProblemDetail body) {
        return new ErrorResponseException(status, body, null);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : "parameter";
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, name + " is invalid");
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Most handlers pass a null body and the base class fills it from the ErrorResponse, so sanitise the result.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setDetail(fixedDetail(ex, problem, status));
            problem.setInstance(route(request));
        }
        return response;
    }

    private static String fixedDetail(Exception ex, ProblemDetail problem, HttpStatusCode status) {
        if (ex instanceof MethodArgumentTypeMismatchException m) {
            return m.getName() + " is invalid";
        }
        if (ex instanceof MissingServletRequestParameterException m) {
            return m.getParameterName() + " is required";
        }
        // ApiProblems throws exactly ErrorResponseException with a fixed detail; subclasses are Spring's own.
        if (ex.getClass() == ErrorResponseException.class && problem.getDetail() != null) {
            return problem.getDetail();
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "Request failed" : known.getReasonPhrase();
    }

    /** The matched route template, from the handler mapping (not from the request), or {@code /}. */
    private static URI route(WebRequest request) {
        Object pattern =
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (pattern instanceof String template && template.startsWith("/api/")) {
            return URI.create(template.replace("{", "%7B").replace("}", "%7D"));
        }
        return URI.create("/");
    }
}
