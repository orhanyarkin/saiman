package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalStatus;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventAppender;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentTestAccess;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.github.orhanyarkin.x402.client.PaymentIntent;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup crash recovery: what a process that died mid-run left in the database is resolved fail
 * closed (unsent reservations released, signed payments held, unfinished runs INTERRUPTED), and a
 * second pass changes nothing.
 */
class SpendRecoveryTests extends SpendTestSupport {

    @Autowired
    private BudgetSpendGuard guard;

    @Autowired
    private SpendRecovery recovery;

    @Autowired
    private RunEventAppender eventLog;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void anInterruptedRunsTerminalEventCarriesItsCommittedAndLlmCost() {
        UUID run = createRun(50_000);
        jdbc.sql("UPDATE run SET committed_atomic = 10000, llm_cost_usd_micros = 1234 WHERE id = :id")
                .param("id", run)
                .update();

        recovery.recover();

        assertThat(eventLog.readAfter(run, 0))
                .extracting(RunEvent::data)
                .containsExactly(new RunEventData.RunFailed(
                        SpendRecovery.INTERRUPTED, RunCost.of(Money.usdc(10_000), Money.usdMicros(1_234))));
    }

    @Test
    void aCrashedProcessIsRecoveredFailClosedAndIdempotently() {
        UUID run = createRun(50_000);
        PaymentIntentHandle reserved = reserve(run, 10_000);
        PaymentIntentHandle signed = reserve(run, 12_000);
        setStatus(signed, "SIGNED");
        PaymentIntentHandle approved = newIntent(run);
        setStatus(approved, "APPROVED");
        UUID finishedRun = createRun(50_000);
        jdbc.sql("UPDATE run SET status = 'SUCCEEDED' WHERE id = :id")
                .param("id", finishedRun)
                .update();
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 22_000, 0));

        SpendRecovery.Outcome first = recovery.recover();

        assertThat(first).isEqualTo(new SpendRecovery.Outcome(1, 1, 1));
        assertThat(status(reserved)).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(status(signed)).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(status(approved)).isEqualTo(PaymentIntentStatus.RELEASED);
        // The held 12000 keeps counting on the run and the day; the released 10000 does not.
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 12_000, 0));
        assertThat(today()).isEqualTo(new RunCounters(0, 12_000, 0));
        assertThat(runStatus(run)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_code FROM run WHERE id = :id")
                        .param("id", run)
                        .query(String.class)
                        .single())
                .isEqualTo(SpendRecovery.INTERRUPTED);
        assertThat(runStatus(finishedRun)).isEqualTo("SUCCEEDED");
        // the interrupted run's stream ends with a terminal event carrying the persisted cost so far
        List<RunEvent> events = eventLog.readAfter(run, 0);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(RunEventType.RUN_FAILED);
        assertThat(events.get(0).data())
                .isEqualTo(new RunEventData.RunFailed(
                        SpendRecovery.INTERRUPTED, RunCost.of(Money.usdc(0), Money.usdMicros(0))));
        assertThat(eventLog.readAfter(finishedRun, 0)).isEmpty();

        assertThat(recovery.recover()).isEqualTo(new SpendRecovery.Outcome(0, 0, 0));
        assertThat(eventLog.readAfter(run, 0)).hasSize(1); // no second terminal event
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 12_000, 0));
        assertThat(today()).isEqualTo(new RunCounters(0, 12_000, 0));
    }

    @Test
    void anInterruptedRunLeavesNoOpenApprovalOrIntent() {
        UUID run = createRun(50_000);
        PaymentIntentHandle approved = newIntent(run);
        setStatus(approved, "APPROVED");
        PaymentIntentHandle pending = newIntent(run);
        PaymentIntentHandle awaiting = newIntent(run);
        setStatus(awaiting, "AWAITING_APPROVAL");
        UUID approvalId = new TransactionTemplate(transactionManager)
                .execute(status -> approvals.request(
                        awaiting.id(), run, 18_000, FakeSeller.PAY_TO, "http://seller/x", Duration.ofMinutes(5)));

        recovery.recover();

        assertThat(status(approved)).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(status(pending)).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(status(awaiting)).isEqualTo(PaymentIntentStatus.EXPIRED);
        assertThat(intents.find(awaiting.id()).orElseThrow().denyReason()).isEqualTo(DenyReason.APPROVAL_EXPIRED);
        assertThat(approvals.find(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
        assertThat(recovery.recover()).isEqualTo(new SpendRecovery.Outcome(0, 0, 0));
    }

    private PaymentIntentHandle reserve(UUID run, long amount) {
        PaymentIntentHandle handle = newIntent(run);
        guard.reserve(new PaymentIntent(
                PaymentTestAccess.idempotencyKey(handle),
                handle.resource(),
                new PaymentRequirements(
                        TestnetAssets.SCHEME_EXACT,
                        TestnetAssets.NETWORK,
                        Long.toString(amount),
                        TestnetAssets.USDC_ADDRESS,
                        FakeSeller.PAY_TO,
                        60,
                        Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION))));
        assertThat(status(handle)).isEqualTo(PaymentIntentStatus.RESERVED);
        return handle;
    }

    private void setStatus(PaymentIntentHandle handle, String status) {
        jdbc.sql("UPDATE payment_intent SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", handle.id())
                .update();
    }

    private PaymentIntentStatus status(PaymentIntentHandle handle) {
        return intents.find(handle.id()).orElseThrow().status();
    }

    private String runStatus(UUID run) {
        return jdbc.sql("SELECT status FROM run WHERE id = :id")
                .param("id", run)
                .query(String.class)
                .single();
    }
}
