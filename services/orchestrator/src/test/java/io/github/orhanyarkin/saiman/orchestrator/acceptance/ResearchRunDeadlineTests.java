package io.github.orhanyarkin.saiman.orchestrator.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalStatus;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalView;
import io.github.orhanyarkin.saiman.orchestrator.run.FailureCode;
import io.github.orhanyarkin.saiman.orchestrator.run.RunStatus;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.AgentRunTestSupport;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The run's wall-clock deadline (1.5 s here) ends an approval wait long before the approval timeout
 * (4 s): the approval expires, nothing is signed for it, and the run fails with {@code RUN_DEADLINE}.
 */
@TestPropertySource(properties = "saiman.orchestrator.runs.deadline=1500ms")
class ResearchRunDeadlineTests extends AgentRunTestSupport {

    private static final String QUESTIONS_PATH = "/v1/disclosures/THYAO/questions";

    @Autowired
    private ApprovalService approvals;

    @Test
    void aRunPastItsDeadlineExpiresItsApprovalAndFailsWithRunDeadline() {
        seller.price(QUESTIONS_PATH, 16_000); // above the 15000 threshold: waits for a human
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(ASK, askArgs("THYAO", "How large is the fuel hedge?")),
                Reply.text("notes"),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001")));

        UUID runId = runId(startRun("What did THYAO disclose about fuel costs?", null));
        List<RunEvent> events = awaitTerminal(runId);

        assertThat(events.getLast().data())
                .isInstanceOfSatisfying(
                        RunEventData.RunFailed.class,
                        failed -> assertThat(failed.failureCode()).isEqualTo(FailureCode.RUN_DEADLINE.name()));
        assertThat(summary(runId).status()).isEqualTo(RunStatus.FAILED);
        RunEventData.PaymentApprovalRequired required =
                data(events, RunEventData.PaymentApprovalRequired.class).getFirst();
        assertThat(data(events, RunEventData.PaymentApprovalDecided.class))
                .containsExactly(new RunEventData.PaymentApprovalDecided(required.approvalId(), "EXPIRED"));
        ApprovalView approval = approvals.find(required.approvalId()).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approval.decidedAt()).isNotNull();
        assertThat(Duration.between(approval.requestedAt(), approval.decidedAt()))
                .isLessThan(Duration.ofSeconds(4)); // the deadline, not the approval timeout, ended the wait
        assertThat(signer.calls()).isZero();
        assertThat(intentsWithStatus(runId, "APPROVED")).isZero();
        assertThat(intentsWithStatus(runId, "AWAITING_APPROVAL")).isZero();
        assertThat(count(events, RunEventType.STEP_STARTED)).isLessThan(4); // no step after the deadline
        assertThat(run(runId)).isEqualTo(new RunCounters(50_000, 0, 0));
    }
}
