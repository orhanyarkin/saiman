package io.github.orhanyarkin.saiman.orchestrator.approval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** No approval with this id belongs to this run. Rendered as a 404 Problem Details response. */
public class ApprovalNotFoundException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public ApprovalNotFoundException() {
        super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "approval not found"), null);
    }
}
