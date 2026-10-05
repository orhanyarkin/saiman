package io.github.orhanyarkin.saiman.orchestrator.approval;

import io.github.orhanyarkin.saiman.orchestrator.dashboard.BoundedReads;
import io.github.orhanyarkin.saiman.orchestrator.openapi.ProblemDetailSchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/runs/{runId}/approvals/{approvalId}}: a human approves or rejects one payment
 * above the approval threshold. {@link ApiRequestGuardFilter} requires {@code application/json},
 * the {@code X-Saiman-Csrf} header and an allowed {@code Host} first.
 *
 * <p>There is deliberately no endpoint that changes a budget: an approval opens the threshold gate
 * for one payment intent and nothing else.
 */
@RestController
class ApprovalController {

    static final int MAX_LIST = 100;

    private final ApprovalService approvals;
    private final BoundedReads reads;

    ApprovalController(ApprovalService approvals, BoundedReads reads) {
        this.approvals = approvals;
        this.reads = reads;
    }

    @Operation(operationId = "decideApproval", summary = "Approve or reject one payment above the approval threshold")
    @ApiResponse(
            responseCode = "400",
            description = "decision is missing or invalid",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such approval in this run",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Already decided, expired, or the run has ended",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "200",
            description = "The approval after the decision",
            content = @Content(schema = @Schema(implementation = ApprovalResponse.class)))
    @PostMapping(
            path = "/api/v1/runs/{runId}/approvals/{approvalId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApprovalResponse decide(
            @PathVariable UUID runId, @PathVariable UUID approvalId, @RequestBody DecisionRequest request) {
        ApprovalDecision decision = request.decision();
        if (decision == null) {
            throw problem(HttpStatus.BAD_REQUEST, "decision must be APPROVE or REJECT");
        }
        ApprovalService.DecisionOutcome outcome = approvals.decide(runId, approvalId, decision);
        if (!outcome.applied()) {
            throw problem(
                    HttpStatus.CONFLICT,
                    outcome.approval().status() == ApprovalStatus.EXPIRED
                            ? "approval has expired"
                            : "approval was already decided");
        }
        return new ApprovalResponse(approvalId, outcome.approval().status());
    }

    /**
     * {@code GET /api/v1/approvals?status=PENDING}: approvals in one status (default PENDING), newest
     * first, at most {@value #MAX_LIST} rows. The status is read as text so a bad value gets a fixed
     * message.
     */
    @Operation(operationId = "listApprovals", summary = "Approvals in one status (default PENDING), newest first")
    @ApiResponse(
            responseCode = "400",
            description = "status is invalid",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetailSchema.class)))
    @ApiResponse(
            responseCode = "200",
            description = "The approvals",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApprovalView.class))))
    @GetMapping(path = "/api/v1/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ApprovalView> list(@RequestParam(required = false) @Nullable String status) {
        ApprovalStatus wanted = ApprovalStatus.PENDING;
        if (status != null) {
            try {
                wanted = ApprovalStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw problem(HttpStatus.BAD_REQUEST, "status must be PENDING, APPROVED, REJECTED or EXPIRED");
            }
        }
        ApprovalStatus requested = wanted;
        return reads.read(() -> approvals.listByStatus(requested, MAX_LIST));
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    /** Request body: {@code {"decision": "APPROVE" | "REJECT"}}. */
    record DecisionRequest(@Nullable ApprovalDecision decision) {}

    /** Response body: the approval's status after the decision. */
    record ApprovalResponse(UUID approvalId, ApprovalStatus status) {}
}
