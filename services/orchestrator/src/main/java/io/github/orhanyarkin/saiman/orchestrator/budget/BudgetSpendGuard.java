package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalStatus;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalView;
import io.github.orhanyarkin.saiman.orchestrator.outbox.PaymentEventPublisher;
import io.github.orhanyarkin.saiman.orchestrator.payment.IntentAuthorization;
import io.github.orhanyarkin.saiman.orchestrator.payment.OfferedPayment;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentView;
import io.github.orhanyarkin.saiman.shared.payments.SettlementEvidence;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.x402.client.PaymentIntent;
import io.github.orhanyarkin.x402.client.SpendDeniedException;
import io.github.orhanyarkin.x402.client.SpendGuard;
import io.github.orhanyarkin.x402.client.SpendReservation;
import io.github.orhanyarkin.x402.client.X402ClientProperties;
import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.UnsupportedPaymentException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The orchestrator's only {@link SpendGuard} (ADR-0013). Because this bean exists, the starter's
 * {@code PropertiesSpendGuard} fallback ({@code @ConditionalOnMissingBean(SpendGuard.class)}) is
 * never created.
 *
 * <p>{@link #reserve} is one Postgres transaction that runs before anything is signed:
 *
 * <ol>
 *   <li>lock the {@code payment_intent} row by idempotency key; an unknown key, or one that is not
 *       PENDING/APPROVED, is refused ({@code UNKNOWN_INTENT}), so only requests created by code can
 *       be paid and a key is never paid twice;
 *   <li>the offer must be payable (the one supported testnet network/asset, a positive amount),
 *       else {@code OFFER_NOT_PAYABLE}; the resource must be the one the intent was created for;
 *       payee on the allowlist; amount at most the per-request maximum;
 *   <li>lock the {@code run} row, then the UTC {@code spend_day} row (fixed order: no deadlock);
 *       {@code reserved + committed + amount <= budget} for both (a DB CHECK backs the run's); the
 *       run's paid calls stay under {@code max-paid-calls-per-run} ({@code MAX_PAID_CALLS});
 *   <li>under a transaction-scoped advisory lock, the wallet's paid calls created in the last hour
 *       (all runs) stay under {@code max-paid-calls-per-hour} ({@code HOURLY_PAID_CALLS}), so the
 *       seller's per-payer hourly limit never answers a signed retry with 429;
 *   <li>strictly above the approval threshold without an APPROVED approval, the intent goes
 *       AWAITING_APPROVAL and the payment is refused for now; an APPROVED intent whose fresh offer
 *       differs from what the human approved is refused ({@code APPROVAL_MISMATCH});
 *   <li>otherwise the amount is added to both reserved counters and the intent becomes RESERVED.
 * </ol>
 *
 * A refusal is committed (the intent records its {@link DenyReason}) before {@link
 * SpendDeniedException} is thrown. The starter's exception type is final, so there is no {@code
 * ApprovalRequiredException} subtype: callers read the intent's status (AWAITING_APPROVAL) instead.
 *
 * <p>Held reservations (signed, outcome unknown) keep counting, because only {@link #commit} and
 * {@link #release} ever take an amount out of {@code reserved_atomic}.
 */
@Component
public class BudgetSpendGuard implements SpendGuard {

    private static final Logger LOG = LoggerFactory.getLogger(BudgetSpendGuard.class);
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final String DECISIONS_METRIC = "saiman.spend.decisions";
    /** Postgres advisory-lock key of the hourly paid-call count ("SAIMAN" + 0x0001). */
    private static final long HOURLY_COUNT_LOCK = 0x5341494D414E0001L;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PaymentIntentService intents;
    private final ApprovalService approvals;
    private final SpendProperties spend;
    private final Set<String> allowedPayTo;
    private final long maxAmountPerRequest;
    private final MeterRegistry meters;
    private final PaymentEventPublisher events;

    public BudgetSpendGuard(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            PaymentIntentService intents,
            ApprovalService approvals,
            SpendProperties spend,
            X402ClientProperties x402,
            MeterRegistry meters,
            PaymentEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.intents = intents;
        this.approvals = approvals;
        this.spend = spend;
        this.allowedPayTo = normalizeAllowlist(x402);
        Long max = x402.maxAmountPerRequest();
        // Unset: every payment is over the maximum (fail closed). The interceptor refuses to start
        // without it anyway once a signing key is configured.
        this.maxAmountPerRequest = max == null ? 0 : max;
        this.meters = meters;
        String warning = approvalThresholdWarning(spend.approvalThresholdAtomic(), maxAmountPerRequest);
        if (warning != null) {
            LOG.warn(warning);
        }
    }

    /**
     * A warning if no payment can ever need a human: the per-request maximum refuses everything above
     * it, so a threshold at or above that maximum never triggers. Not fatal: the limits still hold,
     * the approval queue is just unused.
     */
    static @Nullable String approvalThresholdWarning(long approvalThresholdAtomic, long maxAmountPerRequest) {
        if (approvalThresholdAtomic < maxAmountPerRequest) {
            return null;
        }
        return "saiman.orchestrator.spend.approval-threshold-atomic (" + approvalThresholdAtomic
                + ") is not below x402.client.max-amount-per-request (" + maxAmountPerRequest
                + "): no payment can ever need a human approval";
    }

    @Override
    public SpendReservation reserve(PaymentIntent intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        Decision decision = Objects.requireNonNull(tx.execute(status -> decide(intent)));
        meters.counter(DECISIONS_METRIC, "outcome", decision.outcome(), "reason", decision.reasonTag())
                .increment();
        if (decision.outcome().equals(Decision.GRANTED)) {
            return new SpendReservation(intent.idempotencyKey(), intent);
        }
        // Fixed messages only: nothing from the seller's offer or the model reaches an exception.
        throw new SpendDeniedException(
                decision.outcome().equals(Decision.APPROVAL_REQUIRED)
                        ? "payment is above the approval threshold and waits for a human decision"
                        : "payment refused by the spend-control plane");
    }

    /**
     * Records what M4 needs to reconcile the authorization ({@code from}, {@code nonce}, {@code
     * validBefore}) while nothing has left the process. Throws unless the signed recipient and
     * amount are exactly what was reserved; the interceptor then drops the signature unsent and
     * releases the reservation.
     */
    @Override
    public void signed(SpendReservation reservation, Eip3009Authorization authorization) {
        long amount = parseAmount(authorization.value());
        long validBefore;
        try {
            validBefore = Long.parseLong(authorization.validBefore());
        } catch (NumberFormatException e) {
            throw new SpendDeniedException("signed authorization has an invalid validBefore");
        }
        // One transaction: the SIGNED row and its PaymentAuthorized publication commit together, or neither
        // does and the interceptor drops the signature unsent (ADR-0016).
        boolean recorded = Boolean.TRUE.equals(tx.execute(status -> intents.markSigned(
                        reservation.idempotencyKey(),
                        authorization.to(),
                        amount,
                        authorization.from(),
                        authorization.nonce(),
                        validBefore)
                .map(events::authorized)
                .isPresent()));
        if (!recorded) {
            throw new SpendDeniedException("the signed authorization does not match the reservation");
        }
    }

    /**
     * SIGNED -> SETTLED: moves the amount from reserved to committed on the run and the day, and publishes
     * {@code PaymentSettled} (BUYER, FACILITATOR) in the same transaction.
     */
    @Override
    public void commit(SpendReservation reservation, SettlementResponse settlement) {
        tx.executeWithoutResult(status -> {
            PaymentIntentView view = lockSigned(reservation);
            long amount = Objects.requireNonNull(view.amountAtomic());
            LocalDate day = Objects.requireNonNull(view.reservedDay());
            lockRun(view.runId());
            lockDay(day);
            moveRun(view.runId(), amount, true);
            moveDay(day, amount, true);
            intents.markSettled(view.id(), settlement.transaction());
            IntentAuthorization settled = intents.findAuthorization(view.id())
                    .orElseThrow(() -> new IllegalStateException("settled intent without an authorization"));
            events.settled(settled, settlement.transaction(), SettlementEvidence.FACILITATOR);
        });
    }

    /**
     * HELD -> SETTLED for an intent the caller has locked, in the caller's transaction (HELD resolution,
     * ADR-0018): the chain says the authorization was used, so the amount moves from reserved to committed on
     * the run and on the day it was reserved, and {@code PaymentSettled} (BUYER, CHAIN) is published.
     *
     * @param txHash the transaction that used it, or null if the log lookup found none
     */
    void settleHeldLocked(IntentAuthorization held, @Nullable String txHash) {
        requireHeld(held);
        lockRun(held.runId());
        lockDay(held.reservedDay());
        moveRun(held.runId(), held.amountAtomic(), true);
        moveDay(held.reservedDay(), held.amountAtomic(), true);
        intents.markHeldSettled(held.id(), txHash);
        events.settled(held, txHash, SettlementEvidence.CHAIN);
    }

    /**
     * HELD -> RELEASED for an intent the caller has locked, in the caller's transaction: the chain says the
     * authorization expired unused, so the amount leaves the reserved counters and {@code PaymentFailed}
     * (BUYER, FINAL, {@code expired_unused}) is published.
     */
    void releaseHeldLocked(IntentAuthorization held) {
        requireHeld(held);
        lockRun(held.runId());
        lockDay(held.reservedDay());
        moveRun(held.runId(), held.amountAtomic(), false);
        moveDay(held.reservedDay(), held.amountAtomic(), false);
        intents.markHeldReleased(held.id());
        events.expiredUnused(held);
    }

    private static void requireHeld(IntentAuthorization intent) {
        if (intent.status() != PaymentIntentStatus.HELD) {
            throw new IllegalStateException("only a held payment can be resolved from the chain");
        }
    }

    /**
     * RESERVED -> RELEASED: only for a reservation whose signature never left the process (the
     * interceptor calls this only then). A SIGNED, HELD or SETTLED intent is never released.
     */
    @Override
    public void release(SpendReservation reservation, String reason) {
        tx.executeWithoutResult(status -> {
            PaymentIntentView view = intents.lockByIdempotencyKey(reservation.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("unknown payment intent"));
            releaseLocked(view);
        });
    }

    /**
     * RESERVED -> RELEASED for an intent the caller has locked, in the caller's transaction: takes
     * the amount out of the run's and the day's reserved counters. Also used by {@link
     * SpendRecovery} at startup.
     */
    void releaseLocked(PaymentIntentView view) {
        if (view.status() != PaymentIntentStatus.RESERVED) {
            throw new IllegalStateException("only an unsent reservation can be released");
        }
        long amount = Objects.requireNonNull(view.amountAtomic());
        LocalDate day = Objects.requireNonNull(view.reservedDay());
        lockRun(view.runId());
        lockDay(day);
        moveRun(view.runId(), amount, false);
        moveDay(day, amount, false);
        intents.markReleased(view.id());
    }

    private Decision decide(PaymentIntent intent) {
        PaymentIntentView view =
                intents.lockByIdempotencyKey(intent.idempotencyKey()).orElse(null);
        if (view == null
                || (view.status() != PaymentIntentStatus.PENDING && view.status() != PaymentIntentStatus.APPROVED)) {
            // Unknown or already-used key: nothing to record on (or overwrite of) another intent.
            return Decision.denied(DenyReason.UNKNOWN_INTENT);
        }
        OfferedPayment offer = payableOffer(intent);
        if (offer == null) {
            // Nothing from an unpayable offer is recorded (an amount of 0 would not even fit the row).
            return deny(view, DenyReason.OFFER_NOT_PAYABLE, null);
        }
        if (!view.resource().equals(intent.resource().toString())) {
            return deny(view, DenyReason.UNKNOWN_INTENT, offer);
        }
        if (!allowedPayTo.contains(offer.payTo())) {
            return deny(view, DenyReason.PAYEE_NOT_ALLOWED, offer);
        }
        if (offer.amountAtomic() > maxAmountPerRequest) {
            return deny(view, DenyReason.OVER_PER_REQUEST_MAX, offer);
        }

        RunCounters run = lockRun(view.runId());
        if (paidCalls(view.runId()) >= spend.maxPaidCallsPerRun()) {
            return deny(view, DenyReason.MAX_PAID_CALLS, offer);
        }
        if (Math.addExact(Math.addExact(run.reserved(), run.committed()), offer.amountAtomic()) > run.budget()) {
            return deny(view, DenyReason.RUN_BUDGET, offer);
        }
        LocalDate day = today();
        DayCounters dayCounters = lockDay(day);
        if (Math.addExact(Math.addExact(dayCounters.reserved(), dayCounters.committed()), offer.amountAtomic())
                > spend.dailyCapAtomic()) {
            return deny(view, DenyReason.DAILY_CAP, offer);
        }
        lockHourlyCount();
        if (paidCallsInLastHour() >= spend.maxPaidCallsPerHour()) {
            return deny(view, DenyReason.HOURLY_PAID_CALLS, offer);
        }

        if (view.status() == PaymentIntentStatus.APPROVED) {
            // The fresh 402 must be exactly what the human approved.
            ApprovalView approval = approvals.findForIntent(view.id()).orElse(null);
            if (approval == null
                    || approval.status() != ApprovalStatus.APPROVED
                    || approval.amountAtomic() != offer.amountAtomic()
                    || !approval.payTo().equals(offer.payTo())
                    || !approval.resource().equals(view.resource())) {
                return deny(view, DenyReason.APPROVAL_MISMATCH, offer);
            }
        } else if (offer.amountAtomic() > spend.approvalThresholdAtomic()) {
            intents.markAwaitingApproval(view.id(), offer);
            approvals.request(
                    view.id(),
                    view.runId(),
                    offer.amountAtomic(),
                    offer.payTo(),
                    view.resource(),
                    spend.approvalTimeout());
            return Decision.approvalRequired();
        }

        // Conditional updates: the WHERE clauses repeat the checks, and the run's CHECK constraint
        // backs the budget invariant even if this code were wrong.
        int runUpdated = jdbc.sql("""
                        UPDATE run SET reserved_atomic = reserved_atomic + :amount
                         WHERE id = :id AND reserved_atomic + committed_atomic + :amount <= budget_atomic
                        """)
                .param("id", view.runId())
                .param("amount", offer.amountAtomic())
                .update();
        int dayUpdated = jdbc.sql("""
                        UPDATE spend_day SET reserved_atomic = reserved_atomic + :amount
                         WHERE day = :day AND reserved_atomic + committed_atomic + :amount <= :cap
                        """)
                .param("day", day)
                .param("amount", offer.amountAtomic())
                .param("cap", spend.dailyCapAtomic())
                .update();
        if (runUpdated != 1 || dayUpdated != 1) {
            throw new IllegalStateException("budget counters changed under a row lock");
        }
        intents.markReserved(view.id(), offer, day);
        return Decision.granted();
    }

    /**
     * The offer as the guard records it, or null if it can't be paid at all: not the one supported
     * scheme/network/asset ({@link TestnetAssets#requireSupported}), or an amount that is malformed
     * or not positive. The interceptor checks the same before calling the guard; this is the guard's
     * own, independent check.
     */
    private static @Nullable OfferedPayment payableOffer(PaymentIntent intent) {
        PaymentRequirements requirements = intent.requirements();
        long amount;
        try {
            TestnetAssets.requireSupported(requirements);
            amount = AssetAmount.parse(requirements.amount()).atomicUnits();
        } catch (UnsupportedPaymentException | IllegalArgumentException e) {
            return null;
        }
        if (amount <= 0) {
            return null;
        }
        return new OfferedPayment(
                amount, requirements.payTo().toLowerCase(Locale.ROOT), requirements.network(), requirements.asset());
    }

    private Decision deny(PaymentIntentView view, DenyReason reason, @Nullable OfferedPayment offer) {
        intents.markDenied(view.id(), reason, offer);
        return Decision.denied(reason);
    }

    private PaymentIntentView lockSigned(SpendReservation reservation) {
        PaymentIntentView view = intents.lockByIdempotencyKey(reservation.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("unknown payment intent"));
        if (view.status() != PaymentIntentStatus.SIGNED) {
            throw new IllegalStateException("only a signed payment can be committed");
        }
        return view;
    }

    private RunCounters lockRun(UUID runId) {
        return jdbc.sql("SELECT budget_atomic, reserved_atomic, committed_atomic FROM run WHERE id = :id FOR UPDATE")
                .param("id", runId)
                .query((rs, row) -> new RunCounters(
                        rs.getLong("budget_atomic"), rs.getLong("reserved_atomic"), rs.getLong("committed_atomic")))
                .single();
    }

    private DayCounters lockDay(LocalDate day) {
        jdbc.sql("INSERT INTO spend_day (day) VALUES (:day) ON CONFLICT (day) DO NOTHING")
                .param("day", day)
                .update();
        return jdbc.sql("SELECT reserved_atomic, committed_atomic FROM spend_day WHERE day = :day FOR UPDATE")
                .param("day", day)
                .query((rs, row) -> new DayCounters(rs.getLong("reserved_atomic"), rs.getLong("committed_atomic")))
                .single();
    }

    private int paidCalls(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM payment_intent WHERE run_id = :id"
                        + " AND status IN ('RESERVED', 'SIGNED', 'SETTLED', 'HELD')")
                .param("id", runId)
                .query(Integer.class)
                .single();
    }

    /**
     * Serialises the hourly count across runs until this transaction ends. The day row lock already
     * does so within one UTC day; this lock also covers two reservations that straddle midnight and
     * lock different day rows. Taken last and only here, so it adds no lock-order cycle.
     */
    private void lockHourlyCount() {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS locked")
                .param("key", HOURLY_COUNT_LOCK)
                .query(Integer.class)
                .single();
    }

    /**
     * Intents that may move money (reserved, signed, settled or held), created in the last hour by
     * the database clock, across all runs. No payer filter: the orchestrator pays from one wallet,
     * and a RESERVED intent has no payer recorded yet.
     */
    private int paidCallsInLastHour() {
        return jdbc.sql("SELECT count(*) FROM payment_intent"
                        + " WHERE status IN ('RESERVED', 'SIGNED', 'SETTLED', 'HELD')"
                        + " AND created_at > now() - interval '1 hour'")
                .query(Integer.class)
                .single();
    }

    /** Takes {@code amount} out of reserved and, on commit, adds it to committed. */
    private void moveRun(UUID runId, long amount, boolean commit) {
        int updated = jdbc.sql("UPDATE run SET reserved_atomic = reserved_atomic - :amount,"
                        + " committed_atomic = committed_atomic + :committed"
                        + " WHERE id = :id AND reserved_atomic >= :amount")
                .param("id", runId)
                .param("amount", amount)
                .param("committed", commit ? amount : 0L)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("run reservation counter is inconsistent");
        }
    }

    private void moveDay(LocalDate day, long amount, boolean commit) {
        int updated = jdbc.sql("UPDATE spend_day SET reserved_atomic = reserved_atomic - :amount,"
                        + " committed_atomic = committed_atomic + :committed"
                        + " WHERE day = :day AND reserved_atomic >= :amount")
                .param("day", day)
                .param("amount", amount)
                .param("committed", commit ? amount : 0L)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("daily reservation counter is inconsistent");
        }
    }

    private LocalDate today() {
        // The database clock decides the UTC day, so every instance agrees on it.
        return jdbc.sql("SELECT (now() AT TIME ZONE 'UTC')::date")
                .query(LocalDate.class)
                .single();
    }

    private static long parseAmount(String wireValue) {
        try {
            return AssetAmount.parse(wireValue).atomicUnits();
        } catch (IllegalArgumentException e) {
            throw new SpendDeniedException("payment amount is not a valid atomic-unit value");
        }
    }

    private static Set<String> normalizeAllowlist(X402ClientProperties x402) {
        return x402.allowedPayTo().stream()
                .filter(address -> !address.isBlank())
                .map(address -> {
                    if (!ADDRESS.matcher(address).matches()) {
                        throw new IllegalStateException(
                                "x402.client.allowed-pay-to entries must each be a 0x-prefixed 20-byte hex address");
                    }
                    return address.toLowerCase(Locale.ROOT);
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private record RunCounters(long budget, long reserved, long committed) {}

    private record DayCounters(long reserved, long committed) {}

    private record Decision(String outcome, @Nullable DenyReason reason) {
        static final String GRANTED = "granted";
        static final String DENIED = "denied";
        static final String APPROVAL_REQUIRED = "approval_required";

        static Decision granted() {
            return new Decision(GRANTED, null);
        }

        static Decision denied(DenyReason reason) {
            return new Decision(DENIED, reason);
        }

        static Decision approvalRequired() {
            return new Decision(APPROVAL_REQUIRED, null);
        }

        String reasonTag() {
            return reason == null ? "none" : reason.name().toLowerCase(Locale.ROOT);
        }
    }
}
