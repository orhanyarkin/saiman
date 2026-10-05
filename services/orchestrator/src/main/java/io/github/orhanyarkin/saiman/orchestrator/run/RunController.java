package io.github.orhanyarkin.saiman.orchestrator.run;

import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/runs} and {@code GET /api/v1/runs/{runId}}. The request guard requires
 * {@code application/json} and {@code X-Saiman-Csrf: 1} on the POST. There is deliberately no
 * endpoint that changes a budget: it is chosen once here, bounded by the configured maximum.
 */
@RestController
class RunController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final RunService runs;

    RunController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping(
            path = "/api/v1/runs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StartRunResponse> start(@RequestBody StartRunRequest request) {
        RunService.StartedRun started = runs.start(request.question(), request.budgetAtomic());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/runs/" + started.runId()))
                .body(new StartRunResponse(started.runId(), started.eventsUrl(), started.traceId()));
    }

    @GetMapping(path = "/api/v1/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    RunSummary summary(@PathVariable UUID runId) {
        return runs.summary(runId).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "run not found"));
    }

    /**
     * Newest-first keyset page. Parameters are read as text and validated here, so a bad value gets a
     * fixed message instead of Spring's type-mismatch detail (which would echo the input).
     */
    @GetMapping(path = "/api/v1/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    RunPage list(
            @RequestParam(required = false) @Nullable String limit,
            @RequestParam(required = false) @Nullable String before) {
        int size = DEFAULT_LIMIT;
        if (limit != null) {
            if (!limit.matches("\\d{1,3}") || Integer.parseInt(limit) < 1 || Integer.parseInt(limit) > MAX_LIMIT) {
                throw problem(HttpStatus.BAD_REQUEST, "limit must be an integer between 1 and " + MAX_LIMIT);
            }
            size = Integer.parseInt(limit);
        }
        RunCursor cursor = null;
        if (before != null) {
            cursor = RunCursor.decode(before);
            if (cursor == null) {
                throw problem(HttpStatus.BAD_REQUEST, "before must be a cursor returned by this endpoint");
            }
        }
        return runs.page(cursor, size);
    }

    @ExceptionHandler(RunAdmissionException.class)
    ResponseEntity<ProblemDetail> rejected(RunAdmissionException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID_QUESTION, INVALID_BUDGET -> HttpStatus.BAD_REQUEST;
            case TOO_MANY_RUNS -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_READY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header("Retry-After", "5");
        }
        return response.body(ProblemDetail.forStatusAndDetail(status, e.reason().detail()));
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    /**
     * {@code {"question": "...", "budgetAtomic": 50000}}; {@code budgetAtomic} is optional (USDC
     * atomic units). Unknown fields are ignored: nothing else can influence a run.
     */
    record StartRunRequest(
            @Nullable String question, @Nullable Long budgetAtomic) {}

    /** {@code 202}: where to follow the run and its trace. */
    record StartRunResponse(
            UUID runId, String eventsUrl, @Nullable String traceId) {}
}
