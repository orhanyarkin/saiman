package io.github.orhanyarkin.saiman.orchestrator.approval;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and decides approvals. Lock order: the {@code approval} row, then its {@code
 * payment_intent} row (the spend guard never locks an approval row, so there is no cycle).
 *
 * <p>A decision changes only the approval and its intent's status: approving opens the threshold
 * gate for that one intent, and the spend guard still checks the run budget and the daily cap when
 * the request is re-sent.
 */
@Service
public class ApprovalService {

    private static final String COLUMNS =
            "id, payment_intent_id, run_id, amount_atomic, pay_to, resource, status, requested_at, decided_at,"
                    + " expires_at, decided_by";

    private final JdbcClient jdbc;
    private final PaymentIntentService intents;
    private final ApplicationEventPublisher events;

    public ApprovalService(JdbcClient jdbc, PaymentIntentService intents, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.intents = intents;
        this.events = events;
    }

    /**
     * Inserts a PENDING approval for an intent the spend guard just moved to AWAITING_APPROVAL, as
     * part of the guard's transaction.
     *
     * @return the new approval's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID request(
            UUID paymentIntentId, UUID runId, long amountAtomic, String payTo, String resource, Duration timeout) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO approval (id, payment_intent_id, run_id, amount_atomic, pay_to, resource, status,
                                              expires_at)
                        VALUES (:id, :intentId, :runId, :amount, :payTo, :resource, 'PENDING',
                                now() + :timeoutMillis * interval '1 millisecond')
                        """)
                .param("id", id)
                .param("intentId", paymentIntentId)
                .param("runId", runId)
                .param("amount", amountAtomic)
                .param("payTo", payTo)
                .param("resource", resource)
                .param("timeoutMillis", timeout.toMillis())
                .update();
        return id;
    }

    public Optional<ApprovalView> find(UUID approvalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM approval WHERE id = :id")
                .param("id", approvalId)
                .query(ApprovalService::map)
                .optional();
    }

    /** Approvals in one status, newest request first, at most {@code limit}. */
    public List<ApprovalView> listByStatus(ApprovalStatus status, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM approval WHERE status = :status"
                        + " ORDER BY requested_at DESC, id DESC LIMIT :limit")
                .param("status", status.name())
                .param("limit", limit)
                .query(ApprovalService::map)
                .list();
    }

    public Optional<ApprovalView> findForIntent(UUID paymentIntentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM approval WHERE payment_intent_id = :id")
                .param("id", paymentIntentId)
                .query(ApprovalService::map)
                .optional();
    }

    /**
     * Applies a human's decision to a PENDING approval of {@code runId}. A decision that arrives
     * after {@code expires_at} expires the approval instead; a decided approval is left unchanged.
     *
     * <p>The run's status is read after the approval row is locked. A run finishing concurrently
     * locks the same approval rows first (it expires them) and then closes the run's open intents, so
     * either the run is already terminal here (refused) or the finish sees this decision and releases
     * an APPROVED intent that will never be sent.
     *
     * @param decidedBy the authenticated principal name ({@code <role>:<8 hex of the token digest>}), stored as the
     *     audit trail; never a token
     * @throws ApprovalNotFoundException if no approval with this id belongs to this run
     * @throws RunAlreadyFinishedException if the run has ended
     */
    @Transactional
    public DecisionOutcome decide(UUID runId, UUID approvalId, ApprovalDecision decision, String decidedBy) {
        LockedApproval locked = jdbc.sql("SELECT " + COLUMNS + ", expires_at <= now() AS expired FROM approval"
                        + " WHERE id = :id AND run_id = :runId FOR UPDATE")
                .param("id", approvalId)
                .param("runId", runId)
                .query((rs, row) -> new LockedApproval(map(rs, row), rs.getBoolean("expired")))
                .optional()
                .orElseThrow(ApprovalNotFoundException::new);
        boolean runFinished = jdbc.sql("SELECT status IN ('SUCCEEDED', 'FAILED') FROM run WHERE id = :runId")
                .param("runId", runId)
                .query(Boolean.class)
                .optional()
                .orElse(true);
        if (runFinished) {
            throw new RunAlreadyFinishedException();
        }
        ApprovalView approval = locked.approval();
        if (approval.status() != ApprovalStatus.PENDING) {
            return new DecisionOutcome(approval, false);
        }
        if (locked.expired()) {
            return new DecisionOutcome(apply(approval, ApprovalStatus.EXPIRED, null), false);
        }
        ApprovalStatus status =
                decision == ApprovalDecision.APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        return new DecisionOutcome(apply(approval, status, decidedBy), true);
    }

    /**
     * Expires the approval if it is still PENDING (the waiter's timeout).
     *
     * @return the approval's final status (a decision that won the race is kept)
     */
    @Transactional
    public ApprovalStatus expire(UUID approvalId) {
        ApprovalView approval = jdbc.sql("SELECT " + COLUMNS + " FROM approval WHERE id = :id FOR UPDATE")
                .param("id", approvalId)
                .query(ApprovalService::map)
                .optional()
                .orElseThrow(ApprovalNotFoundException::new);
        if (approval.status() != ApprovalStatus.PENDING) {
            return approval.status();
        }
        return apply(approval, ApprovalStatus.EXPIRED, null).status();
    }

    /**
     * Expires every PENDING approval of a run that has ended (T4 hook for the run lifecycle), and
     * with it the AWAITING_APPROVAL intent: a stale approval can no longer be approved. Locks the
     * approval rows in id order, then their intents (the usual approval -> payment_intent order).
     *
     * @return how many approvals were expired
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int expirePendingForRun(UUID runId) {
        List<ApprovalView> pending = jdbc.sql("SELECT " + COLUMNS + " FROM approval"
                        + " WHERE run_id = :runId AND status = 'PENDING' ORDER BY id FOR UPDATE")
                .param("runId", runId)
                .query(ApprovalService::map)
                .list();
        pending.forEach(approval -> apply(approval, ApprovalStatus.EXPIRED, null));
        return pending.size();
    }

    /** {@code decidedBy} is null for an expiry: nobody decided. */
    private ApprovalView apply(ApprovalView approval, ApprovalStatus status, @Nullable String decidedBy) {
        ApprovalView updated = jdbc.sql("UPDATE approval SET status = :status, decided_at = now(), decided_by = :by"
                        + " WHERE id = :id AND status = 'PENDING' RETURNING " + COLUMNS)
                .param("id", approval.id())
                .param("status", status.name())
                .param("by", decidedBy)
                .query(ApprovalService::map)
                .single();
        switch (status) {
            case APPROVED ->
                intents.markApprovalOutcome(approval.paymentIntentId(), PaymentIntentStatus.APPROVED, null);
            case REJECTED ->
                intents.markApprovalOutcome(
                        approval.paymentIntentId(), PaymentIntentStatus.REJECTED, DenyReason.APPROVAL_REJECTED);
            case EXPIRED ->
                intents.markApprovalOutcome(
                        approval.paymentIntentId(), PaymentIntentStatus.EXPIRED, DenyReason.APPROVAL_EXPIRED);
            case PENDING -> throw new IllegalArgumentException("PENDING is not a decision");
        }
        events.publishEvent(new ApprovalDecidedEvent(approval.id(), status));
        return updated;
    }

    /**
     * The API shows only the role of the decider ({@code operator}), never the digest fragment of the principal name
     * ({@code operator:1a2b3c4d}), which any READER could otherwise see. The column keeps the full name for audit.
     */
    private static @Nullable String roleOf(@Nullable String principalName) {
        if (principalName == null) {
            return null;
        }
        int colon = principalName.indexOf(':');
        return colon < 0 ? principalName : principalName.substring(0, colon);
    }

    private static ApprovalView map(ResultSet rs, int row) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ApprovalView(
                rs.getObject("id", UUID.class),
                rs.getObject("payment_intent_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getLong("amount_atomic"),
                rs.getString("pay_to"),
                rs.getString("resource"),
                ApprovalStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("requested_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                roleOf(rs.getString("decided_by")));
    }

    /**
     * The result of {@link #decide}.
     *
     * @param approval the approval after the call
     * @param applied true if the human's decision was applied; false if the approval was already
     *     decided or had expired
     */
    public record DecisionOutcome(ApprovalView approval, boolean applied) {}

    private record LockedApproval(ApprovalView approval, boolean expired) {}
}
