package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.llm.RunGuardUnavailableException;
import io.github.orhanyarkin.saiman.sellerapi.llm.RunLimitExceededException;
import io.github.orhanyarkin.saiman.sellerapi.retrieval.RetrievalUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every RAG failure to a non-2xx RFC 9457 body with a fixed, generic detail. All of these are
 * thrown from the handler method, i.e. after the x402 starter verified the payment but before it
 * settles, and a non-2xx handler response is never settled. The details never contain exception
 * messages, the question, chunk text or keys.
 *
 * <p>Unlike {@code TickerNotFoundException} these do not extend {@code ErrorResponseException}:
 * they are thrown from deep inside services and stay free of HTTP concepts.
 */
@RestControllerAdvice
class DisclosureProblemAdvice {

    @ExceptionHandler(RetrievalUnavailableException.class)
    ProblemDetail retrievalUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Disclosure retrieval is temporarily unavailable");
    }

    @ExceptionHandler(ModelUnavailableException.class)
    ProblemDetail modelUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Answer generation is temporarily unavailable");
    }

    @ExceptionHandler(RunLimitExceededException.class)
    ProblemDetail runLimitExceeded() {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many answers requested right now; retry later");
    }

    @ExceptionHandler(RunGuardUnavailableException.class)
    ProblemDetail runGuardUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Answer generation is temporarily unavailable");
    }

    @ExceptionHandler(MalformedModelOutputException.class)
    ProblemDetail malformedModelOutput() {
        return problem(HttpStatus.BAD_GATEWAY, "The answer could not be produced");
    }

    @ExceptionHandler(InsufficientCitationsException.class)
    ProblemDetail insufficientCitations() {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "No sufficiently grounded answer could be produced");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
