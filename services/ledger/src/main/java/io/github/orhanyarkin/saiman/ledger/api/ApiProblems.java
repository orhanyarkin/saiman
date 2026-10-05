package io.github.orhanyarkin.saiman.ledger.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Problem Details (RFC 9457) for the read endpoints, rendered by Spring MVC's own exception handling. Detail texts
 * are fixed: nothing from the request is echoed (same rule as {@link LedgerApiGuardFilter}).
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
