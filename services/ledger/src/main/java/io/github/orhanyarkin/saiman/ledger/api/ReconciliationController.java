package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationReport;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationReports;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationRunList;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationService;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation runs (ADR-0018), behind {@link LedgerApiGuardFilter}: a POST needs JSON and the CSRF header.
 * {@code make recon-run} and {@code make recon-report} call these; the dashboard reads the history.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {

    /** Largest history page. */
    static final int MAX_LIMIT = 100;

    private final ReconciliationService service;
    private final ReconciliationReports reports;

    public ReconciliationController(ReconciliationService service, ReconciliationReports reports) {
        this.service = service;
        this.reports = reports;
    }

    /**
     * Starts a run in the background: 202 {@code {runId}}, 409 if one is in progress, 429 (with
     * {@code Retry-After}) if the previous manual run started less than {@code min-manual-interval} ago, 503 without a
     * chain client.
     */
    @PostMapping
    @ApiResponse(responseCode = "202", description = "The run started in the background", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "409",
            description = "A run is in progress",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "429",
            description = "The previous manual run started too recently",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = "Seconds to wait",
                            schema = @Schema(type = "integer")),
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "No Base Sepolia client is configured",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReconciliationStarted> start() {
        UUID runId = service.start()
                .orElseThrow(
                        () -> ApiProblems.problem(HttpStatus.CONFLICT, "A reconciliation run is already in progress"));
        return ResponseEntity.accepted().body(new ReconciliationStarted(runId));
    }

    /** The most recently started runs, newest first, without items. */
    @GetMapping
    @ApiResponse(responseCode = "200", description = "Runs, newest first", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "limit outside 1..100",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    public ReconciliationRunList runs(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiProblems.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        return reports.history(limit);
    }

    /** The most recently started run's report (it may still be RUNNING). */
    @GetMapping("/latest")
    @ApiResponse(responseCode = "200", description = "The latest run's report", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "No run yet (empty body)", content = @Content)
    public ResponseEntity<ReconciliationReport> latest() {
        return ResponseEntity.of(reports.latest());
    }

    /** One run's report. */
    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "The run's report", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "id is not a UUID",
            content = @Content(mediaType = ApiDocs.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such run (empty body)", content = @Content)
    public ResponseEntity<ReconciliationReport> report(@PathVariable UUID id) {
        return ResponseEntity.of(reports.report(id));
    }

    @ExceptionHandler(ReconciliationService.TooSoonException.class)
    ResponseEntity<ProblemDetail> tooSoon(ReconciliationService.TooSoonException e) {
        long seconds = Math.max(1, (e.retryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.TOO_MANY_REQUESTS, "A reconciliation run was started recently; retry later"));
    }

    @ExceptionHandler(ReconciliationService.ChainNotConfiguredException.class)
    ResponseEntity<ProblemDetail> chainNotConfigured(ReconciliationService.ChainNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "No Base Sepolia client is configured"));
    }
}
