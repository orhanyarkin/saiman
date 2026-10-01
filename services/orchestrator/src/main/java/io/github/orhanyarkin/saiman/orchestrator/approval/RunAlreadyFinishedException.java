package io.github.orhanyarkin.saiman.orchestrator.approval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A decision for an approval of a run that has ended. Rendered as a 409 Problem Details response. */
public class RunAlreadyFinishedException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public RunAlreadyFinishedException() {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "run has finished"), null);
    }
}
