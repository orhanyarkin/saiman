package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentTestAccess;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.x402.client.PaymentIntent;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
        assertThat(status(approved)).isEqualTo(PaymentIntentStatus.APPROVED);
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

        assertThat(recovery.recover()).isEqualTo(new SpendRecovery.Outcome(0, 0, 0));
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 12_000, 0));
        assertThat(today()).isEqualTo(new RunCounters(0, 12_000, 0));
    }

    @Test
    void anApprovedIntentStaysApprovedAndUnreserved() {
        UUID run = createRun(50_000);
        PaymentIntentHandle approved = newIntent(run);
        setStatus(approved, "APPROVED");

        recovery.recover();

        assertThat(status(approved)).isEqualTo(PaymentIntentStatus.APPROVED);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
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
