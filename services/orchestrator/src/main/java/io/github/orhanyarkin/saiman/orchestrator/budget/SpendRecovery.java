package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventAppender;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentView;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Crash recovery for the spend-control plane (ADR-0013), run once at startup in one transaction.
 * Nothing in this process can be mid-payment yet, so every non-final state left in the database
 * belongs to a process that died:
 *
 * <ul>
 *   <li><b>RESERVED -> RELEASED</b>, counters decremented: {@code SpendGuard.signed} records SIGNED
 *       before a signature may leave the process, so a RESERVED intent provably sent nothing.
 *   <li><b>SIGNED -> HELD</b>: the signature may have been sent; the amount keeps counting in the
 *       reserved counters until M4 reconciles it on chain (fail closed).
 *   <li><b>Unfinished runs</b> (QUEUED, RUNNING, AWAITING_APPROVAL) become FAILED with {@code
 *       failure_code = INTERRUPTED}; their PENDING approvals expire, their PENDING/APPROVED intents
 *       are released and AWAITING_APPROVAL ones expire (nothing was reserved for them), and each gets
 *       a terminal {@code RUN_FAILED} event with the cost so far, so its event stream ends; their
 *       in-process virtual thread is gone.
 * </ul>
 *
 * Idempotent: a second pass finds nothing to do. Assumes one orchestrator instance per database
 * (the orchestrator is one wallet); a second live instance's RESERVED intents would be released
 * under it. A money inconsistency (a counter below the reserved amount) fails startup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SpendRecovery implements ApplicationRunner {

    static final String INTERRUPTED = "INTERRUPTED";
    private static final Logger LOG = LoggerFactory.getLogger(SpendRecovery.class);
    private static final String METRIC = "saiman.spend.recovery";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PaymentIntentService intents;
    private final BudgetSpendGuard guard;
    private final RunEventAppender events;
    private final ApprovalService approvals;
    private final MeterRegistry meters;

    SpendRecovery(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            PaymentIntentService intents,
            BudgetSpendGuard guard,
            RunEventAppender events,
            ApprovalService approvals,
            MeterRegistry meters) {
        this.approvals = approvals;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.intents = intents;
        this.guard = guard;
        this.events = events;
        this.meters = meters;
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /** One recovery pass (package-private so tests can run it again). */
    Outcome recover() {
        Outcome outcome = Objects.requireNonNull(tx.execute(status -> {
            // Lock order as everywhere else: payment_intent -> run -> spend_day.
            List<PaymentIntentView> reserved = intents.lockAllReserved();
            reserved.forEach(guard::releaseLocked);
            int held = intents.holdAllSigned();
            List<InterruptedRun> interrupted = jdbc.sql("""
                            UPDATE run SET status = 'FAILED', failure_code = :code, finished_at = now()
                             WHERE status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL')
                            RETURNING id, committed_atomic, llm_cost_usd_micros
                            """)
                    .param("code", INTERRUPTED)
                    .query((rs, row) -> new InterruptedRun(
                            rs.getObject("id", UUID.class),
                            RunCost.of(
                                    Money.usdc(rs.getLong("committed_atomic")),
                                    Money.usdMicros(rs.getLong("llm_cost_usd_micros")))))
                    .list();
            // Each interrupted run: its PENDING approvals expire and its unsent intents close (as the
            // normal finish does), then the terminal event, so its stream and export end (ADR-0014).
            for (InterruptedRun run : interrupted) {
                approvals.expirePendingForRun(run.id());
                intents.closeUnsentForRun(run.id());
                events.append(run.id(), RunEventType.RUN_FAILED, new RunEventData.RunFailed(INTERRUPTED, run.cost()));
            }
            return new Outcome(reserved.size(), held, interrupted.size());
        }));
        meters.counter(METRIC, "action", "released").increment(outcome.released());
        meters.counter(METRIC, "action", "held").increment(outcome.held());
        meters.counter(METRIC, "action", "interrupted").increment(outcome.interruptedRuns());
        if (outcome.released() + outcome.held() + outcome.interruptedRuns() > 0) {
            LOG.warn(
                    "Spend recovery after an unclean stop: released {} unsent reservation(s), held {} signed"
                            + " payment(s), failed {} unfinished run(s) as INTERRUPTED",
                    outcome.released(),
                    outcome.held(),
                    outcome.interruptedRuns());
        }
        return outcome;
    }

    private record InterruptedRun(UUID id, RunCost cost) {}

    /** What one recovery pass changed. */
    record Outcome(int released, int held, int interruptedRuns) {}
}
