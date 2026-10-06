package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.github.orhanyarkin.saiman.evmrpc.BaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.BlockTag;
import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.ChainProperties;
import io.github.orhanyarkin.saiman.evmrpc.ChainUnavailableException;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.JournalRepository;
import io.github.orhanyarkin.saiman.ledger.payment.ChainState;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentRepository;
import io.github.orhanyarkin.saiman.ledger.payment.SellerState;
import io.github.orhanyarkin.saiman.ledger.reconciliation.SellerCreditNoteClient.SellerCreditNote;
import io.github.orhanyarkin.saiman.ledger.reconciliation.SellerCreditNoteClient.SellerUnauthorizedException;
import io.github.orhanyarkin.saiman.ledger.reconciliation.SellerCreditNoteClient.SellerUnavailableException;
import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.ledger.MismatchKind;
import io.github.orhanyarkin.saiman.shared.ledger.ReconciliationMismatch;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation runs against Base Sepolia (ADR-0018, docs/design/m4-ledger.md).
 *
 * <p><b>Single runner.</b> A run holds a Postgres session-level advisory lock ({@code pg_try_advisory_lock}) on a
 * dedicated connection for its whole duration, so two ledger replicas (or the scheduler and an HTTP request)
 * never reconcile at once; an in-process flag answers the common case without a round trip. A run that finds the
 * lock taken is refused (HTTP 409), not queued.
 *
 * <p><b>Steps.</b> Read the {@code safe} block (unavailable: the run is FAILED, nothing checked); internal checks
 * (unbalanced entries, from SQL); select due payments; for each, read the chain <em>outside</em> any transaction
 * (no row lock is held across RPC latency), then in its own transaction lock the projection row, compute the
 * payment's net movements, apply the pure {@link ChainReconciler}, and write the projection, the ADJUSTMENT entry,
 * the mismatches, the item and the {@code EntryPosted} / {@code ReconciliationMismatch} publications (Spring
 * Modulith registry, same transaction). If a producer changed the row between the read and the lock, the evidence
 * no longer covers it and the pure step says PENDING. {@link ChainUnavailableException} on an item makes it
 * PENDING and the run PARTIAL — never a mismatch.
 *
 * <p><b>Clock bound.</b> Every due, grace and expiry decision uses the <em>effective</em> chain time {@code
 * min(safe.timestamp, now)}: neither a fast RPC nor a fast local clock alone can make an authorization final early.
 * A safe block more than {@value #MAX_SAFE_AHEAD_SECONDS} s ahead of the local clock is a lying or broken RPC: the
 * run still performs its internal checks, skips the chain part, ends PARTIAL and counts {@code
 * saiman.ledger.reconciliation.skipped{reason=safe_in_future}}. The block <em>number</em> is never clamped, so
 * receipts and {@code authorizationState} are still read at the safe block.
 *
 * <p><b>Credit notes (ADR-0021, M4b audit).</b> A credit note touches no wallet account, so the chain cannot
 * contradict a forged one. For every payment whose seller book is CREDITED, the run therefore asks seller-api
 * ({@link SellerCreditNoteClient}, outside any transaction, like the chain reads) whether it recorded that credit
 * note:
 *
 * <ul>
 *   <li><b>Corroborated</b> (same tx hash, case-insensitive, and amount): cached in {@code
 *       credit_note_corroboration}, never asked again; the item is decided by the chain as before.
 *   <li><b>No row, or another tx hash or amount:</b> a {@value #CREDIT_NOTE_UNCORROBORATED} mismatch, never MATCHED.
 *       <em>Nothing is posted</em>: the chain did not move, so there is no wallet difference for suspense, and
 *       reversing the CREDIT_NOTE automatically would let one unauthenticated record (or a seller-side data loss)
 *       rewrite the books. The liability stays until a human posts a REVERSAL. Recorded in {@code
 *       reconciliation_mismatch} and the report, counted, and (since M6, design B3) published once on {@code
 *       ledger.reconciliation-mismatch.v1} with null chain fields; findings recorded before M6 are not republished.
 *   <li><b>Seller unreachable or no definite answer:</b> the item is PENDING and the run PARTIAL, exactly like an
 *       unavailable chain: never MATCHED by default, never a false finding.
 * </ul>
 */
@Service
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationService {

    /** Advisory lock key of the single runner ({@code "saiman-recon"} as ASCII). */
    static final long LOCK_KEY = 0x7361696d616e7263L;

    /** The only network the ledger accepts (V1 check constraint). */
    static final String NETWORK = AuthorizationRef.BASE_SEPOLIA;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** A safe block further ahead of the local clock than this is not trusted: the run skips its chain part. */
    static final long MAX_SAFE_AHEAD_SECONDS = 60;

    /** A CREDITED payment whose credit note seller-api does not confirm (V5; {@code MismatchKind} since M6). */
    static final String CREDIT_NOTE_UNCORROBORATED = "CREDIT_NOTE_UNCORROBORATED";
    /** Base produces a block every two seconds; used only to aim the bounded log search. */
    private static final long SECONDS_PER_BLOCK = 2;

    private final ObjectProvider<BaseSepoliaUsdc> chainProvider;
    private final ObjectProvider<ChainProperties> chainProperties;
    private final ReconciliationProperties properties;
    private final ReconciliationRepository repository;
    private final PaymentRepository payments;
    private final JournalRepository journal;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final SellerCreditNoteClient sellers;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<@Nullable Instant> lastManualStart = new AtomicReference<>();
    private final AtomicReference<@Nullable UUID> lastUnauthorizedRun = new AtomicReference<>();
    private final AtomicLong unbalancedEntries = new AtomicLong();
    private final AtomicLong dueBacklog = new AtomicLong();
    private final AtomicLong oldestUncheckedSeconds = new AtomicLong();

    public ReconciliationService(
            ObjectProvider<BaseSepoliaUsdc> chainProvider,
            ObjectProvider<ChainProperties> chainProperties,
            ReconciliationProperties properties,
            ReconciliationRepository repository,
            PaymentRepository payments,
            JournalRepository journal,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            DataSource dataSource,
            ObjectProvider<Clock> clock,
            MeterRegistry meters,
            ObjectProvider<ObservationRegistry> observations,
            SellerCreditNoteClient sellers) {
        this.chainProvider = chainProvider;
        this.chainProperties = chainProperties;
        this.properties = properties;
        this.repository = repository;
        this.payments = payments;
        this.journal = journal;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.dataSource = dataSource;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.meters = meters;
        this.observations = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        this.sellers = sellers;
        meters.gauge("saiman.ledger.reconciliation.unbalanced.entries", unbalancedEntries);
        // Set at the start of each run (after the safe block is read): the backlog the run starts from.
        meters.gauge("saiman.ledger.reconciliation.due", dueBacklog);
        meters.gauge("saiman.ledger.reconciliation.oldest_unchecked_seconds", oldestUncheckedSeconds);
    }

    /** Thrown when no {@link BaseSepoliaUsdc} bean exists ({@code saiman.chain.rpc-url} unset). */
    public static class ChainNotConfiguredException extends RuntimeException {
        ChainNotConfiguredException() {
            super("No Base Sepolia client is configured (saiman.chain.rpc-url)");
        }
    }

    /** A manual run was requested sooner than {@code min-manual-interval} after the previous one (HTTP 429). */
    public static class TooSoonException extends RuntimeException {
        private final Duration retryAfter;

        TooSoonException(Duration retryAfter) {
            super("A reconciliation run was started recently");
            this.retryAfter = retryAfter;
        }

        /** How long until a manual run is accepted again. */
        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** True when a chain client exists, so runs (and the schedule) make sense. */
    public boolean chainConfigured() {
        return chainProvider.getIfAvailable() != null;
    }

    /**
     * Starts a run on a virtual thread and returns its id at once (the RUNNING row exists when this returns).
     *
     * @return empty if a run is already in progress (here or in another replica)
     */
    public Optional<UUID> start() {
        BaseSepoliaUsdc chain = requireChain();
        Lease lease = acquire();
        if (lease == null) {
            return Optional.empty();
        }
        // Checked after "in progress" (a 409 wins), and only successful starts count. Per replica: the advisory
        // lock already serialises replicas, this only rate-limits the HTTP trigger.
        Instant now = clock.instant();
        Instant previous = lastManualStart.get();
        if (previous != null && now.isBefore(previous.plus(properties.minManualInterval()))) {
            lease.close();
            throw new TooSoonException(Duration.between(now, previous.plus(properties.minManualInterval())));
        }
        lastManualStart.set(now);
        UUID runId = begin(lease);
        Thread.ofVirtual().name("reconciliation-" + runId).start(() -> {
            try (lease) {
                execute(runId, chain);
            }
        });
        return Optional.of(runId);
    }

    /**
     * Runs synchronously (the scheduler; tests).
     *
     * @return the run id, or empty if a run is already in progress
     */
    public Optional<UUID> runNow() {
        BaseSepoliaUsdc chain = requireChain();
        Lease lease = acquire();
        if (lease == null) {
            return Optional.empty();
        }
        try (lease) {
            UUID runId = begin(lease);
            execute(runId, chain);
            return Optional.of(runId);
        }
    }

    private BaseSepoliaUsdc requireChain() {
        BaseSepoliaUsdc chain = chainProvider.getIfAvailable();
        if (chain == null) {
            throw new ChainNotConfiguredException();
        }
        return chain;
    }

    private UUID begin(Lease lease) {
        try {
            repository.failInterruptedRuns(clock.instant());
            UUID runId = UUID.randomUUID();
            repository.insertRun(runId, clock.instant(), NETWORK);
            return runId;
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    private void execute(UUID runId, BaseSepoliaUsdc chain) {
        Observation.createNotStarted("saiman.ledger.reconciliation.run", observations)
                .lowCardinalityKeyValue("network", NETWORK)
                .observe(() -> executeObserved(runId, chain));
    }

    private void executeObserved(UUID runId, BaseSepoliaUsdc chain) {
        Tally tally = new Tally();
        Long safeBlock = null;
        String status;
        try {
            ChainBlock reported = chain.block(BlockTag.SAFE);
            safeBlock = reported.number();
            long unbalanced = repository.unbalancedEntries();
            if (unbalanced > 0) {
                log.error("Reconciliation run {}: {} journal entries do not balance per asset", runId, unbalanced);
            }
            unbalancedEntries.set(unbalanced);
            long now = clock.instant().getEpochSecond();
            if (reported.timestamp() > Math.addExact(now, MAX_SAFE_AHEAD_SECONDS)) {
                // Trusting it would expire live authorizations and post SETTLED_BUT_UNUSED for payments that may
                // still settle. Nothing is checked; a later run with a sane safe block catches up.
                log.warn(
                        "Reconciliation run {}: safe block {} s ahead of the local clock, chain checks skipped",
                        runId,
                        reported.timestamp() - now);
                meters.counter("saiman.ledger.reconciliation.skipped", "reason", "safe_in_future")
                        .increment();
                status = "PARTIAL";
            } else {
                ChainBlock safe = effective(reported, now);
                recordBacklog(safe);
                List<String> due = repository.duePaymentKeys(
                        safe.timestamp(), properties.graceAfterValidBefore().toSeconds(), properties.batchSize());
                for (String key : due) {
                    tally.add(checkOne(runId, key, safe, chain));
                }
                status = tally.unavailable ? "PARTIAL" : "COMPLETED";
            }
        } catch (ChainUnavailableException e) {
            log.warn("Reconciliation run {}: safe block unavailable, nothing checked", runId);
            status = "FAILED";
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} failed: {}", runId, e.getClass().getName());
            status = "FAILED";
        }
        repository.finishRun(runId, status, clock.instant(), safeBlock, tally.counters());
        meters.counter("saiman.ledger.reconciliation.runs", "status", status).increment();
        log.info("Reconciliation run {} {}: {}", runId, status, tally.counters());
    }

    /**
     * The safe block with its timestamp bounded by the local clock: {@code min(safe.timestamp, now)} is the chain
     * time every due, grace and expiry decision uses (here and in {@link ChainReconciler}).
     */
    static ChainBlock effective(ChainBlock safe, long nowEpochSecond) {
        return safe.timestamp() <= nowEpochSecond ? safe : new ChainBlock(safe.number(), nowEpochSecond);
    }

    private void recordBacklog(ChainBlock safe) {
        ReconciliationRepository.Backlog backlog = repository.backlog(
                safe.timestamp(), properties.graceAfterValidBefore().toSeconds());
        dueBacklog.set(backlog.due());
        Instant oldest = backlog.oldestUnchecked();
        oldestUncheckedSeconds.set(
                oldest == null
                        ? 0
                        : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()));
    }

    private record ItemResult(ItemStatus status, boolean resolvedUsed, boolean resolvedUnused, boolean unavailable) {}

    private @Nullable ItemResult checkOne(UUID runId, String key, ChainBlock safe, BaseSepoliaUsdc chain) {
        PaymentProjection snapshot = payments.findByKey(key).orElse(null);
        if (snapshot == null) {
            return null;
        }
        try {
            CreditNoteAnswer creditNote = askSeller(snapshot);
            ChainReconciler.Evidence evidence = fetch(snapshot, safe, chain);
            return transactions.execute(tx -> book(runId, key, evidence, creditNote));
        } catch (ChainUnavailableException e) {
            return skipped(runId, snapshot);
        } catch (SellerUnauthorizedException e) {
            // A wrong or missing service token fails every lookup: one ERROR per run, never the token itself.
            if (!runId.equals(lastUnauthorizedRun.getAndSet(runId))) {
                log.error(
                        "Reconciliation run {}: seller-api refused the ledger's service token ({}); credited payments"
                                + " stay PENDING until saiman.ledger.seller.service-token is fixed",
                        runId,
                        e.getMessage());
            }
            return skipped(runId, snapshot);
        } catch (SellerUnavailableException e) {
            log.warn(
                    "Reconciliation run {}: credit note of payment {} not corroborated yet (seller: {})",
                    runId,
                    snapshot.id(),
                    e.getMessage());
            return skipped(runId, snapshot);
        } catch (RuntimeException e) {
            // Any other failure (lock timeout, bad row, client bug) skips this item only; it is marked checked, so
            // it rotates to the back instead of failing every future run. The payment key carries the nonce and
            // database messages may echo bound values, so only the id and the exception class are logged.
            log.error(
                    "Reconciliation run {}: payment {} skipped after {}",
                    runId,
                    snapshot.id(),
                    e.getClass().getName());
            return skipped(runId, snapshot);
        }
    }

    /** Records an item that could not be checked this run: PENDING, run PARTIAL, never a mismatch. */
    private ItemResult skipped(UUID runId, PaymentProjection snapshot) {
        try {
            transactions.executeWithoutResult(tx -> {
                repository.touch(snapshot.id(), clock.instant());
                repository.insertItem(runId, snapshot.id(), ItemStatus.PENDING, reportedTx(snapshot), null, null);
            });
        } catch (RuntimeException e) {
            log.error(
                    "Reconciliation run {}: could not record payment {} as pending ({})",
                    runId,
                    snapshot.id(),
                    e.getClass().getName());
        }
        meters.counter("saiman.ledger.reconciliation.items", "status", "PENDING")
                .increment();
        return new ItemResult(ItemStatus.PENDING, false, false, true);
    }

    /**
     * What the seller said about a CREDITED payment's credit note, read before the transaction.
     *
     * @param cached the ledger already holds a matching corroboration (the seller was not asked)
     * @param note the seller's credit note, or null when it definitely has none
     */
    private record CreditNoteAnswer(
            boolean cached, @Nullable SellerCreditNote note) {}

    /** Null when the payment is not CREDITED; throws {@link SellerUnavailableException} without a definite answer. */
    private @Nullable CreditNoteAnswer askSeller(PaymentProjection p) {
        if (p.sellerState() != SellerState.CREDITED) {
            return null;
        }
        String tx = p.sellerTxHash();
        if (tx != null
                && repository.creditNoteCorroborated(p.id(), tx, p.amount().atomicUnits())) {
            return new CreditNoteAnswer(true, null);
        }
        return new CreditNoteAnswer(false, sellers.find(p.paymentKey()).orElse(null));
    }

    private enum Corroboration {
        NOT_CREDITED,
        CORROBORATED,
        UNCORROBORATED,
        /** CREDITED now, but the seller was not asked (the row changed after it was read): decide next run. */
        UNKNOWN
    }

    private static Corroboration corroboration(PaymentProjection p, @Nullable CreditNoteAnswer answer) {
        if (p.sellerState() != SellerState.CREDITED) {
            return Corroboration.NOT_CREDITED;
        }
        if (answer == null) {
            return Corroboration.UNKNOWN;
        }
        if (answer.cached()) {
            return Corroboration.CORROBORATED;
        }
        SellerCreditNote note = answer.note();
        String ledgerTx = p.sellerTxHash();
        boolean same = note != null
                && ledgerTx != null
                && note.txHash().equals(PaymentProjection.lower(ledgerTx))
                && note.amountAtomic() == p.amount().atomicUnits();
        return same ? Corroboration.CORROBORATED : Corroboration.UNCORROBORATED;
    }

    private @Nullable ItemResult book(
            UUID runId, String key, ChainReconciler.Evidence evidence, @Nullable CreditNoteAnswer creditNote) {
        PaymentProjection p = payments.lock(key).orElse(null);
        if (p == null) {
            return null;
        }
        Instant now = clock.instant();
        ChainReconciler.Outcome outcome =
                ChainReconciler.reconcile(p, repository.nets(p), evidence, properties.settings(), runId, now);
        repository.updateChain(outcome.next(), outcome.chainBlock());
        JournalEntry adjustment = outcome.adjustment();
        UUID adjustmentId = null;
        if (adjustment != null) {
            journal.post(adjustment);
            events.publishEvent(PaymentLedgerService.entryPosted(adjustment, runId.toString(), now));
            adjustmentId = adjustment.id();
            meters.counter("saiman.ledger.entries", "kind", adjustment.kind().name())
                    .increment();
        }
        for (ChainReconciler.Finding finding : outcome.findings()) {
            UUID mismatchId = mismatchId(p.id(), finding);
            UUID linked = finding.adjusted() ? adjustmentId : null;
            if (repository.insertMismatch(mismatchId, runId, p, finding, linked)) {
                events.publishEvent(new ReconciliationMismatch(
                        new EventMetadata(mismatchId.toString(), now, PaymentLedgerService.CONSUMER, runId.toString()),
                        mismatchId,
                        runId,
                        p.id(),
                        finding.kind(),
                        finding.ledgerValue(),
                        finding.chainValue(),
                        finding.reportedTxHash(),
                        finding.chainTxHash(),
                        linked));
                meters.counter(
                                "saiman.ledger.reconciliation.mismatches",
                                "kind",
                                finding.kind().name())
                        .increment();
            }
        }
        ChainReconciler.Finding primary = outcome.primary();
        String txHash = outcome.next().chainTxHash() != null ? outcome.next().chainTxHash() : reportedTx(p);
        ItemStatus status = outcome.status();
        Corroboration corroboration = corroboration(p, creditNote);
        SellerCreditNote sellerNote = creditNote == null ? null : creditNote.note();
        if (corroboration == Corroboration.CORROBORATED && sellerNote != null) {
            repository.insertCreditNoteCorroboration(p.id(), sellerNote.txHash(), sellerNote.amountAtomic(), now);
        } else if (corroboration == Corroboration.UNKNOWN && status == ItemStatus.MATCHED) {
            status = ItemStatus.PENDING;
        } else if (corroboration == Corroboration.UNCORROBORATED) {
            status = ItemStatus.MISMATCH;
            uncorroborated(runId, p, sellerNote);
        }
        if (primary == null && corroboration == Corroboration.UNCORROBORATED) {
            repository.insertItem(
                    runId,
                    p.id(),
                    status,
                    txHash,
                    CREDIT_NOTE_UNCORROBORATED,
                    p.amount().atomicUnits(),
                    sellerNote == null ? null : sellerNote.amountAtomic(),
                    null);
        } else {
            repository.insertItem(
                    runId,
                    p.id(),
                    status,
                    txHash,
                    primary,
                    primary != null && primary.adjusted() ? adjustmentId : null);
        }
        meters.counter("saiman.ledger.reconciliation.items", "status", status.name())
                .increment();
        boolean wasUnknown = p.chainState() == ChainState.UNKNOWN;
        return new ItemResult(
                status,
                wasUnknown && outcome.next().chainState() == ChainState.USED,
                wasUnknown && outcome.next().chainState() == ChainState.UNUSED,
                false);
    }

    /**
     * Records a {@value #CREDIT_NOTE_UNCORROBORATED} finding once per payment: the seller has no credit note for it,
     * or one with another tx hash or amount. Nothing is posted (see the class comment). Runs inside the item's
     * transaction, so the {@link ReconciliationMismatch} publication (Modulith registry, the outbox) commits with the
     * finding or not at all; it is published only when this call inserted the row, so a rerun publishes nothing.
     */
    private void uncorroborated(UUID runId, PaymentProjection p, @Nullable SellerCreditNote seller) {
        UUID mismatchId = mismatchId(p.id(), CREDIT_NOTE_UNCORROBORATED);
        boolean inserted = repository.insertMismatch(
                mismatchId,
                runId,
                p,
                CREDIT_NOTE_UNCORROBORATED,
                p.amount().atomicUnits(),
                seller == null ? null : seller.amountAtomic(),
                p.sellerTxHash(),
                seller == null ? null : seller.txHash(),
                null);
        if (inserted) {
            Instant now = clock.instant();
            // Event semantics (design B3): ledgerAmount = the credited amount, reportedTxHash = the seller's tx hash
            // from the books; the chain said nothing, so chainAmount, chainTxHash and adjustmentEntryId are null.
            events.publishEvent(new ReconciliationMismatch(
                    new EventMetadata(mismatchId.toString(), now, PaymentLedgerService.CONSUMER, runId.toString()),
                    mismatchId,
                    runId,
                    p.id(),
                    MismatchKind.CREDIT_NOTE_UNCORROBORATED,
                    p.amount(),
                    null,
                    p.sellerTxHash(),
                    null,
                    null));
            log.warn(
                    "Reconciliation run {}: payment {} is CREDITED but seller-api {} the credit note",
                    runId,
                    p.id(),
                    seller == null ? "has no record of" : "records different values for");
            meters.counter("saiman.ledger.reconciliation.mismatches", "kind", CREDIT_NOTE_UNCORROBORATED)
                    .increment();
        }
    }

    /** Reads what the chain says about one payment; every call may throw {@link ChainUnavailableException}. */
    private ChainReconciler.Evidence fetch(PaymentProjection p, ChainBlock safe, BaseSepoliaUsdc chain) {
        List<String> reported = ChainReconciler.reportedTxHashes(p);
        Map<String, Optional<UsdcReceipt>> receipts = new HashMap<>();
        for (String tx : reported) {
            receipts.put(tx, chain.receipt(tx));
        }
        Boolean used = null;
        UsdcReceipt found = null;
        if (ChainReconciler.needsAuthorizationState(p, receipts, safe)) {
            used = chain.authorizationState(p.payer(), p.nonce(), safe.number());
            if (used) {
                if (p.chainTxHash() != null) {
                    found = chain.receipt(p.chainTxHash()).orElse(null);
                } else if (p.chainState() != ChainState.USED) {
                    // Searched once; a used authorization without a tx stays TX_UNKNOWN without re-scanning.
                    long[] range = searchRange(p, safe);
                    found = chain.findAuthorizationTx(p.payer(), p.nonce(), range[0], range[1])
                            .flatMap(chain::receipt)
                            .orElse(null);
                }
            }
        }
        return new ChainReconciler.Evidence(safe, receipts, used, found);
    }

    /** Blocks {@code [from, to]} ending a little after the estimated block of {@code validBefore}. */
    private long[] searchRange(PaymentProjection p, ChainBlock safe) {
        long behind = Math.max(0, Math.subtractExact(safe.timestamp(), p.validBefore())) / SECONDS_PER_BLOCK;
        long estimate = Math.max(0, safe.number() - behind);
        long to = Math.min(safe.number(), Math.addExact(estimate, 150));
        ChainProperties chainConfig = chainProperties.getIfAvailable();
        long cap = chainConfig == null ? properties.logSearchBlocks() : chainConfig.maxLogRangeBlocks();
        long window = Math.min(properties.logSearchBlocks(), cap);
        return new long[] {Math.max(0, Math.addExact(Math.subtractExact(to, window), 1)), to};
    }

    private static @Nullable String reportedTx(PaymentProjection p) {
        return p.buyerTxHash() != null ? p.buyerTxHash() : p.sellerTxHash();
    }

    /** One id per payment and kind, like the table's unique key: a replayed check names the same mismatch. */
    static UUID mismatchId(UUID paymentId, ChainReconciler.Finding finding) {
        return mismatchId(paymentId, finding.kind().name());
    }

    static UUID mismatchId(UUID paymentId, String kind) {
        return UUID.nameUUIDFromBytes(
                ("saiman-ledger:mismatch:" + paymentId + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    /** The single-runner lock: an advisory lock held by one dedicated connection until closed. */
    private final class Lease implements AutoCloseable {

        private final Connection connection;

        Lease(Connection connection) {
            this.connection = connection;
        }

        /**
         * Unlocks, then returns the connection to the pool. If the unlock fails, or Postgres says the lock was not
         * held by this session, the connection is aborted <em>before</em> it is closed: closing first would return
         * a pooled connection that may still hold the session lock, refusing every future run. The pool evicts an
         * aborted connection and Postgres ends the session, which releases the lock.
         */
        @Override
        public void close() {
            try {
                boolean unlocked = false;
                try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, LOCK_KEY);
                    try (ResultSet rs = unlock.executeQuery()) {
                        unlocked = rs.next() && rs.getBoolean(1);
                    }
                } catch (SQLException | RuntimeException e) {
                    log.error(
                            "Could not release the reconciliation lock; aborting its connection ({})",
                            e.getClass().getName());
                }
                if (!unlocked) {
                    abort();
                }
            } finally {
                try {
                    connection.close();
                } catch (SQLException | RuntimeException e) {
                    // Already aborted or broken: the pool evicts it.
                } finally {
                    running.set(false);
                }
            }
        }

        private void abort() {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException e) {
                log.error(
                        "Could not abort the reconciliation lock connection ({})",
                        e.getClass().getName());
            }
        }
    }

    private @Nullable Lease acquire() {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            try (PreparedStatement lock = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                lock.setLong(1, LOCK_KEY);
                try (ResultSet rs = lock.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return new Lease(connection);
                    }
                }
            }
            connection.close();
            running.set(false);
            return null;
        } catch (SQLException e) {
            running.set(false);
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new IllegalStateException("Could not take the reconciliation lock", e);
        }
    }

    /** Run counters. */
    private static final class Tally {
        private int checked;
        private int matched;
        private int pending;
        private int resolvedUsed;
        private int resolvedUnused;
        private int mismatches;
        private boolean unavailable;

        void add(@Nullable ItemResult item) {
            if (item == null) {
                return;
            }
            checked++;
            switch (item.status()) {
                case MATCHED -> matched++;
                case PENDING -> pending++;
                case MISMATCH -> mismatches++;
                case TX_UNKNOWN -> {}
            }
            if (item.resolvedUsed()) {
                resolvedUsed++;
            }
            if (item.resolvedUnused()) {
                resolvedUnused++;
            }
            unavailable |= item.unavailable();
        }

        ReconciliationRepository.Counters counters() {
            return new ReconciliationRepository.Counters(
                    checked, matched, pending, resolvedUsed, resolvedUnused, mismatches);
        }
    }
}
