package io.github.orhanyarkin.saiman.orchestrator.run;

import io.github.orhanyarkin.saiman.orchestrator.dashboard.BoundedReads;
import io.github.orhanyarkin.saiman.orchestrator.openapi.ProblemDetailSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.time.Clock;
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
    private static final URI RUNS_ROUTE = URI.create("/api/v1/runs");

    private final RunService runs;

    private final BoundedReads reads;
    private final Clock clock;

    RunController(RunService runs, BoundedReads reads, Clock clock) {
        this.runs = runs;
        this.reads = reads;
        this.clock = clock;
    }

    @Operation(operationId = "startRun", summary = "Start a research run")
    @ApiResponse(
            responseCode = "202",
            description = "Run admitted; follow it at eventsUrl",
            content = @Content(schema = @Schema(implementation = StartRunResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid question or budget",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Too many runs in flight; Retry-After says when to try again",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The service is not ready; Retry-After says when to try again",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
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

    @Operation(operationId = "getRun", summary = "One run: status, budget, spend and report")
    @ApiResponse(
            responseCode = "404",
            description = "Unknown run",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "200",
            description = "The run",
            content = @Content(schema = @Schema(implementation = RunSummary.class)))
    @GetMapping(path = "/api/v1/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    RunSummary summary(@PathVariable UUID runId) {
        return runs.summary(runId).orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "run not found"));
    }

    /**
     * Newest-first keyset page. Parameters are read as text and validated here, so a bad value gets a
     * fixed message instead of Spring's type-mismatch detail (which would echo the input).
     */
    @Operation(operationId = "listRuns", summary = "Runs, newest first (keyset pagination)")
    @Parameter(name = "limit", description = "Page size, 1 to 100 (default 20)")
    @Parameter(name = "before", description = "Opaque cursor: the next field of the previous page")
    @ApiResponse(
            responseCode = "400",
            description = "limit or before is invalid",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "200",
            description = "A page of runs, newest first",
            content = @Content(schema = @Schema(implementation = RunPage.class)))
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
            cursor = RunCursor.decode(before, clock.instant());
            if (cursor == null) {
                throw problem(HttpStatus.BAD_REQUEST, "before must be a cursor returned by this endpoint");
            }
        }
        RunCursor position = cursor;
        int pageSize = size;
        return reads.read(() -> runs.page(position, pageSize));
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
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(status, e.reason().detail());
        problem.setInstance(RUNS_ROUTE); // a local handler bypasses the advice, which would set the template
        return response.body(problem);
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
