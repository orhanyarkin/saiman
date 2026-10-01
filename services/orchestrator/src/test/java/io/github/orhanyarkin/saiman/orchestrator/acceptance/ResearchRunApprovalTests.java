package io.github.orhanyarkin.saiman.orchestrator.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.run.RunStatus;
import io.github.orhanyarkin.saiman.orchestrator.run.RunSummary;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.AgentRunTestSupport;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolMessages;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The human-approval gate inside a full run (ADR-0013): the questions tool costs 16000, above the
 * 15000 threshold. The run pauses in AWAITING_APPROVAL with nothing signed, then resumes on the
 * HTTP decision: APPROVE pays the same intent once, REJECT hands the model a fixed string and the
 * run ends consistently without a signature for it.
 */
class ResearchRunApprovalTests extends AgentRunTestSupport {

    private static final String QUESTIONS_PATH = "/v1/disclosures/THYAO/questions";
    private static final long ABOVE_THRESHOLD = 16_000;

    @BeforeEach
    void questionsNeedApproval() {
        seller.price(QUESTIONS_PATH, ABOVE_THRESHOLD);
    }

    /** Starts a run whose second tool call needs approval; returns once the run waits for it. */
    private Waiting startAndAwaitApproval() {
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(SUMMARY, summaryArgs("THYAO")),
                Reply.toolCall(ASK, askArgs("THYAO", "How large is the fuel hedge?")),
                Reply.text("notes kap:1001:0001"),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001")));
        UUID runId = runId(startRun("What did THYAO disclose about fuel costs?", null));

        RunEventData.PaymentApprovalRequired required = (RunEventData.PaymentApprovalRequired)
                awaitEvent(runId, RunEventType.PAYMENT_APPROVAL_REQUIRED).data();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(summary(runId).status()).isEqualTo(RunStatus.AWAITING_APPROVAL));
        http.get()
                .uri("/api/v1/runs/{id}", runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("AWAITING_APPROVAL");

        assertThat(required.amount()).isEqualTo(Money.usdc(ABOVE_THRESHOLD));
        assertThat(required.payTo()).isEqualToIgnoringCase(FakeSeller.PAY_TO);
        assertThat(required.resource()).isEqualTo(seller.baseUrl() + QUESTIONS_PATH);
        // only the first, below-threshold call was signed; the pending one was not
        assertThat(signer.calls()).isEqualTo(1);
        assertThat(seller.paidRequests()).isEqualTo(1);
        assertThat(run(runId)).isEqualTo(new RunCounters(50_000, 0, PRICE));
        return new Waiting(runId, required.approvalId());
    }

    private record Waiting(UUID runId, UUID approvalId) {}

    @Test
    void anApprovedPaymentResumesTheRunAndSettlesOnce() {
        Waiting waiting = startAndAwaitApproval();

        decide(waiting.runId(), waiting.approvalId(), "APPROVE")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("APPROVED");
        List<RunEvent> events = awaitTerminal(waiting.runId());

        assertThat(data(events, RunEventData.PaymentApprovalDecided.class))
                .containsExactly(new RunEventData.PaymentApprovalDecided(waiting.approvalId(), "APPROVED"));
        assertThat(data(events, RunEventData.PaymentSettled.class))
                .extracting(RunEventData.PaymentSettled::amount)
                .containsExactly(Money.usdc(PRICE), Money.usdc(ABOVE_THRESHOLD));
        assertThat(types(events))
                .containsSubsequence(
                        RunEventType.PAYMENT_APPROVAL_REQUIRED,
                        RunEventType.PAYMENT_APPROVAL_DECIDED,
                        RunEventType.PAYMENT_SETTLED,
                        RunEventType.TOOL_CALL_COMPLETED,
                        RunEventType.RUN_COMPLETED);
        assertThat(signer.calls()).isEqualTo(2);
        assertThat(seller.paidRequests()).isEqualTo(2);
        assertThat(seller.paidRequestLines())
                .containsExactly("GET /v1/disclosures/THYAO/summary", "POST " + QUESTIONS_PATH);
        assertThat(approvalStatus(waiting.approvalId())).isEqualTo("APPROVED");

        RunSummary summary = summary(waiting.runId());
        assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run(waiting.runId())).isEqualTo(new RunCounters(50_000, 0, PRICE + ABOVE_THRESHOLD));
        assertConsistentCost(events, summary, PRICE + ABOVE_THRESHOLD);
    }

    @Test
    void aRejectedPaymentGivesTheModelAFixedStringAndIsNeverSigned() {
        Waiting waiting = startAndAwaitApproval();

        decide(waiting.runId(), waiting.approvalId(), "REJECT")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("REJECTED");
        List<RunEvent> events = awaitTerminal(waiting.runId());

        assertThat(data(events, RunEventData.PaymentApprovalDecided.class))
                .containsExactly(new RunEventData.PaymentApprovalDecided(waiting.approvalId(), "REJECTED"));
        assertThat(data(events, RunEventData.PaymentDenied.class))
                .containsExactly(
                        new RunEventData.PaymentDenied(DenyReason.APPROVAL_REJECTED, Money.usdc(ABOVE_THRESHOLD)));
        // what the model got back for the rejected call is the fixed message, nothing else
        String rejected = ToolMessages.denied(DenyReason.APPROVAL_REJECTED);
        assertThat(model.seen().get(3).text()).contains(rejected);
        assertThat(signer.calls()).isEqualTo(1);
        assertThat(seller.paidRequests()).isEqualTo(1);
        assertThat(approvalStatus(waiting.approvalId())).isEqualTo("REJECTED");
        assertThat(intentsWithStatus(waiting.runId(), "REJECTED")).isEqualTo(1);

        // the run still ends on the evidence it paid for, with consistent costs
        RunSummary summary = summary(waiting.runId());
        assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run(waiting.runId())).isEqualTo(new RunCounters(50_000, 0, PRICE));
        assertConsistentCost(events, summary, PRICE);
    }

    private String approvalStatus(UUID approvalId) {
        return jdbc.sql("SELECT status FROM approval WHERE id = :id")
                .param("id", approvalId)
                .query(String.class)
                .single();
    }

    private void assertConsistentCost(List<RunEvent> events, RunSummary summary, long paidAtomic) {
        RunCost expected = RunCost.of(Money.usdc(paidAtomic), llmCostOf(events));
        assertThat(settledOf(events)).isEqualTo(Money.usdc(paidAtomic));
        assertThat(summary.cost()).isEqualTo(expected);
        assertThat(((RunEventData.RunCompleted) events.getLast().data()).cost()).isEqualTo(expected);
    }
}
