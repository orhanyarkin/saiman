package io.github.orhanyarkin.saiman.orchestrator.approval;

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

    /**
     * {@code GET /api/v1/approvals?status=PENDING}: approvals in one status (default PENDING), newest
     * first, at most {@value #MAX_LIST} rows. The status is read as text so a bad value gets a fixed
     * message.
     */
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
        return approvals.listByStatus(wanted, MAX_LIST);
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }

    /** Request body: {@code {"decision": "APPROVE" | "REJECT"}}. */
    record DecisionRequest(@Nullable ApprovalDecision decision) {}

    /** Response body: the approval's status after the decision. */
    record ApprovalResponse(UUID approvalId, ApprovalStatus status) {}
}
