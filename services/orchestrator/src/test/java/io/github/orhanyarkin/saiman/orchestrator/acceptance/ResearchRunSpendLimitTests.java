package io.github.orhanyarkin.saiman.orchestrator.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.agent.ScriptedChatModel.Reply;
import io.github.orhanyarkin.saiman.orchestrator.run.RunStatus;
import io.github.orhanyarkin.saiman.orchestrator.run.RunSummary;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.AgentRunTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Acceptance 2 over full runs: exceeding a budget blocks the payment <b>before signing</b>. The
 * spy signer and the seller's count of {@code PAYMENT-SIGNATURE} requests prove that nothing beyond
 * the budget was signed or sent, while the model keeps asking for more.
 */
class ResearchRunSpendLimitTests extends AgentRunTestSupport {

    /** A researcher that never stops: a new question every round, so no call is a duplicate. */
    private static Reply greedyResearcher(int round) {
        return Reply.toolCall(ASK, askArgs("THYAO", "Question number " + round + " about fuel costs"));
    }

    @Test
    void theRunBudgetPaysExactlyTwiceAndEveryLaterCallIsDeniedBeforeSigning() {
        model.otherwise(byStep(PLAN, ResearchRunSpendLimitTests::greedyResearcher, RISKS, synthesis("kap:1001:0001")));

        UUID runId = runId(startRun("What did THYAO disclose about fuel costs?", 2 * PRICE));
        List<RunEvent> events = awaitTerminal(runId);

        // 12 tool calls reached the gateway (then the loop was cut in code): 2 paid, 10 denied
        assertThat(count(events, RunEventType.TOOL_CALL_REQUESTED)).isEqualTo(MAX_TOOL_CALLS);
        assertThat(count(events, RunEventType.PAYMENT_SETTLED)).isEqualTo(2);
        List<RunEventData.PaymentDenied> denied = data(events, RunEventData.PaymentDenied.class);
        assertThat(denied).hasSize(MAX_TOOL_CALLS - 2).allSatisfy(d -> {
            assertThat(d.reason()).isEqualTo(DenyReason.RUN_BUDGET);
            assertThat(d.amount()).isEqualTo(Money.usdc(PRICE));
        });
        // the first denial comes right after the second settlement
        List<RunEventType> payments = types(events).stream()
                .filter(t -> t == RunEventType.PAYMENT_SETTLED || t == RunEventType.PAYMENT_DENIED)
                .toList();
        assertThat(payments.subList(0, 3))
                .containsExactly(
                        RunEventType.PAYMENT_SETTLED, RunEventType.PAYMENT_SETTLED, RunEventType.PAYMENT_DENIED);

        // never signed, never sent: 2 signatures, 2 signed requests, 12 unpaid 402 probes
        assertThat(signer.calls()).isEqualTo(2);
        assertThat(seller.paidRequests()).isEqualTo(2);
        assertThat(seller.unpaidRequests()).isEqualTo(MAX_TOOL_CALLS);
        assertThat(intentsWithStatus(runId, "SETTLED")).isEqualTo(2);
        assertThat(intentsWithStatus(runId, "DENIED")).isEqualTo(MAX_TOOL_CALLS - 2);
        assertThat(run(runId)).isEqualTo(new RunCounters(2 * PRICE, 0, 2 * PRICE));

        // the run goes on with the evidence it has and reports consistent costs
        RunSummary summary = summary(runId);
        assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertConsistentCost(events, summary, 2 * PRICE);
    }

    @Test
    void theDailyCapBindsAcrossRunsBeforeSigning() {
        // the day already holds 980000 of the 1000000 cap: room for exactly two more 10000 calls
        jdbc.sql("INSERT INTO spend_day (day, reserved_atomic, committed_atomic)"
                        + " VALUES ((now() AT TIME ZONE 'UTC')::date, 0, 980000)")
                .update();
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(SUMMARY, summaryArgs("THYAO")),
                Reply.toolCall(SUMMARY, summaryArgs("ASELS")),
                Reply.text("notes kap:1001:0001"),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001")));
        UUID first = runId(startRun("What did THYAO disclose about fuel costs?", null));
        List<RunEvent> firstEvents = awaitTerminal(first);
        assertThat(count(firstEvents, RunEventType.PAYMENT_SETTLED)).isEqualTo(2);
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 1_000_000));

        // a second run with plenty of budget is refused by the day, not by its own budget
        model.reset();
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(SUMMARY, summaryArgs("GARAN")),
                Reply.toolCall(ASK, askArgs("THYAO", "What about fuel hedging?")),
                Reply.text("I found nothing."));
        UUID second = runId(startRun("What did THYAO disclose about fuel costs?", null));
        List<RunEvent> secondEvents = awaitTerminal(second);

        assertThat(data(secondEvents, RunEventData.PaymentDenied.class))
                .extracting(RunEventData.PaymentDenied::reason)
                .containsExactly(DenyReason.DAILY_CAP, DenyReason.DAILY_CAP);
        assertThat(count(secondEvents, RunEventType.PAYMENT_SETTLED)).isZero();
        assertThat(signer.calls()).isEqualTo(2);
        assertThat(seller.paidRequests()).isEqualTo(2);
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 1_000_000));
        assertThat(run(second)).isEqualTo(new RunCounters(50_000, 0, 0));

        // no evidence -> the run fails, with its LLM cost and no payment
        RunSummary summary = summary(second);
        assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("NO_EVIDENCE");
        assertConsistentCost(secondEvents, summary, 0);
        assertConsistentCost(firstEvents, summary(first), 2 * PRICE);
    }

    @Test
    void anExhaustedLlmBudgetFailsTheRunWithoutAnotherModelCall() {
        // the researcher's final answer reports 130000 output tokens: 156000 micro-USD > the 150000 scope
        model.then(
                Reply.text(PLAN),
                Reply.toolCall(SUMMARY, summaryArgs("THYAO")),
                Reply.text("notes kap:1001:0001").withUsage(1_000, 130_000),
                Reply.text(RISKS),
                Reply.text(synthesis("kap:1001:0001")));

        UUID runId = runId(startRun("What did THYAO disclose about fuel costs?", null));
        List<RunEvent> events = awaitTerminal(runId);

        RunSummary summary = summary(runId);
        assertThat(summary.status()).isEqualTo(RunStatus.FAILED);
        assertThat(summary.failureCode()).isEqualTo("LLM_BUDGET_EXHAUSTED");
        assertThat(model.callCount())
                .as("the risk step's call was refused before it was sent")
                .isEqualTo(3);
        assertThat(count(events, RunEventType.MODEL_CALL_COMPLETED)).isEqualTo(3);
        assertThat(events.getLast().data())
                .isInstanceOfSatisfying(
                        RunEventData.RunFailed.class,
                        f -> assertThat(f.failureCode()).isEqualTo("LLM_BUDGET_EXHAUSTED"));
        // the payment made before the exhaustion still counts
        assertThat(signer.calls()).isEqualTo(1);
        assertConsistentCost(events, summary, PRICE);
        assertThat(summary.cost().llmUsd().atomicUnits()).isGreaterThan(150_000);
    }

    /** Terminal event, run row and the per-call events agree on what the run cost. */
    private void assertConsistentCost(List<RunEvent> events, RunSummary summary, long paidAtomic) {
        RunCost expected = RunCost.of(Money.usdc(paidAtomic), llmCostOf(events));
        assertThat(settledOf(events)).isEqualTo(Money.usdc(paidAtomic));
        assertThat(summary.committed()).isEqualTo(Money.usdc(paidAtomic));
        assertThat(summary.reserved()).isEqualTo(Money.usdc(0));
        assertThat(summary.cost()).isEqualTo(expected);
        RunCost terminal = switch (events.getLast().data()) {
            case RunEventData.RunCompleted c -> c.cost();
            case RunEventData.RunFailed f -> f.costSoFar();
            default -> throw new AssertionError("not a terminal event");
        };
        assertThat(terminal).isEqualTo(expected);
        assertThat(expected.llmUsd().atomicUnits()).isPositive();
    }
}
