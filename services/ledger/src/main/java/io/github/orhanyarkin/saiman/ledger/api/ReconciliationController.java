package io.github.orhanyarkin.saiman.ledger.api;

import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationReport;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationReports;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation runs (ADR-0018), behind {@link LedgerApiGuardFilter}: a POST needs JSON and the CSRF header.
 * {@code make recon-run} and {@code make recon-report} call these.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService service;
    private final ReconciliationReports reports;

    public ReconciliationController(ReconciliationService service, ReconciliationReports reports) {
        this.service = service;
        this.reports = reports;
    }

    /** Starts a run in the background: 202 {@code {runId}}, or 409 if one is in progress. */
    @PostMapping
    public ResponseEntity<?> start() {
        return service.start()
                .<ResponseEntity<?>>map(id -> ResponseEntity.accepted().body(Map.of("runId", id)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ProblemDetail.forStatusAndDetail(
                                HttpStatus.CONFLICT, "A reconciliation run is already in progress")));
    }

    /** The most recently started run's report (it may still be RUNNING). */
    @GetMapping("/latest")
    public ResponseEntity<ReconciliationReport> latest() {
        return ResponseEntity.of(reports.latest());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReconciliationReport> report(@PathVariable UUID id) {
        return ResponseEntity.of(reports.report(id));
    }

    @ExceptionHandler(ReconciliationService.ChainNotConfiguredException.class)
    ResponseEntity<ProblemDetail> chainNotConfigured(ReconciliationService.ChainNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "No Base Sepolia client is configured"));
    }
}
