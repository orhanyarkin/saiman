package io.github.orhanyarkin.saiman.orchestrator.approval;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

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

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    /** Request body: {@code {"decision": "APPROVE" | "REJECT"}}. */
    record DecisionRequest(@Nullable ApprovalDecision decision) {}

    /** Response body: the approval's status after the decision. */
    record ApprovalResponse(UUID approvalId, ApprovalStatus status) {}
}
