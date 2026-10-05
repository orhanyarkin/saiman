package io.github.orhanyarkin.saiman.ledger.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Problem Details (RFC 9457) for the read endpoints. Callers pass fixed texts only: nothing from the request is
 * echoed (same rule as {@link LedgerApiGuardFilter}). The exceptions are rendered by {@link ApiExceptionHandler},
 * which keeps these details and replaces the {@code instance} (Spring's default is the request URI) with the matched
 * route template. Spring MVC's own exceptions (type conversion, missing parameters) go through the same handler and
 * get fixed details too.
 */
final class ApiProblems {

    private ApiProblems() {}

    static ErrorResponseException badRequest(String detail) {
        return problem(HttpStatus.BAD_REQUEST, detail);
    }

    static ErrorResponseException notFound(String detail) {
        return problem(HttpStatus.NOT_FOUND, detail);
    }

    static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
