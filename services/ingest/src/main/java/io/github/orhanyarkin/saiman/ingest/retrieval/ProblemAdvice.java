package io.github.orhanyarkin.saiman.ingest.retrieval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 Problem Details for the internal API. Extends {@link ResponseEntityExceptionHandler}
 * so malformed bodies and validation failures keep Spring's standard 400 mapping; the details
 * never echo request content.
 */
@RestControllerAdvice
class ProblemAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RetrievalController.InvalidChunkIdException.class)
    ProblemDetail invalidChunkId() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid chunk id");
    }

    @ExceptionHandler(RetrievalController.ChunkNotFoundException.class)
    ProblemDetail chunkNotFound() {
        return problem(HttpStatus.NOT_FOUND, "Chunk not found");
    }

    @ExceptionHandler(RetrievalUnavailableException.class)
    ProblemDetail unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Retrieval is temporarily unavailable");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
