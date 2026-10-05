package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.orchestrator.openapi.ProblemDetailSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/runs/{runId}/payments}: the current state of each payment intent of the run,
 * read from the table, so a HELD intent that the chain resolver later settled or released shows its
 * new status even though the resolver emits no run event. Read-only; 404 for an unknown run.
 */
@RestController
class RunPaymentsController {

    private final PaymentIntentService intents;

    RunPaymentsController(PaymentIntentService intents) {
        this.intents = intents;
    }

    @Operation(operationId = "listRunPayments", summary = "Current state of the run's payment intents")
    @ApiResponse(
            responseCode = "404",
            description = "Unknown run",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "200",
            description = "The intents",
            content = @Content(schema = @Schema(implementation = RunPayments.class)))
    @GetMapping(path = "/api/v1/runs/{runId}/payments", produces = MediaType.APPLICATION_JSON_VALUE)
    RunPayments payments(@PathVariable UUID runId) {
        if (!intents.runExists(runId)) {
            throw new ErrorResponseException(
                    HttpStatus.NOT_FOUND,
                    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "run not found"),
                    null);
        }
        return new RunPayments(intents.listForRun(runId));
    }
}
