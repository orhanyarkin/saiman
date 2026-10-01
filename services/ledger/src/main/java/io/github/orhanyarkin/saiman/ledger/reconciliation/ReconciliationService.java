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
import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
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
 */
@Service
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationService {

    /** Advisory lock key of the single runner ({@code "saiman-recon"} as ASCII). */
    static final long LOCK_KEY = 0x7361696d616e7263L;

    /** The only network the ledger accepts (V1 check constraint). */
    static final String NETWORK = AuthorizationRef.BASE_SEPOLIA;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

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
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<@Nullable Instant> lastManualStart = new AtomicReference<>();
    private final AtomicLong unbalancedEntries = new AtomicLong();

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
            Clock clock,
            MeterRegistry meters,
            ObjectProvider<ObservationRegistry> observations) {
        this.chainProvider = chainProvider;
        this.chainProperties = chainProperties;
        this.properties = properties;
        this.repository = repository;
        this.payments = payments;
        this.journal = journal;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.dataSource = dataSource;
        this.clock = clock;
        this.meters = meters;
        this.observations = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        meters.gauge("saiman.ledger.reconciliation.unbalanced.entries", unbalancedEntries);
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
            ChainBlock safe = chain.block(BlockTag.SAFE);
            safeBlock = safe.number();
            long unbalanced = repository.unbalancedEntries();
            if (unbalanced > 0) {
                log.error("Reconciliation run {}: {} journal entries do not balance per asset", runId, unbalanced);
            }
            unbalancedEntries.set(unbalanced);
            List<String> due = repository.duePaymentKeys(
                    safe.timestamp(), properties.graceAfterValidBefore().toSeconds(), properties.batchSize());
            for (String key : due) {
                tally.add(checkOne(runId, key, safe, chain));
            }
            status = tally.unavailable ? "PARTIAL" : "COMPLETED";
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

    private record ItemResult(ItemStatus status, boolean resolvedUsed, boolean resolvedUnused, boolean unavailable) {}

    private @Nullable ItemResult checkOne(UUID runId, String key, ChainBlock safe, BaseSepoliaUsdc chain) {
        PaymentProjection snapshot = payments.findByKey(key).orElse(null);
        if (snapshot == null) {
            return null;
        }
        try {
            ChainReconciler.Evidence evidence = fetch(snapshot, safe, chain);
            return transactions.execute(tx -> book(runId, key, evidence));
        } catch (ChainUnavailableException e) {
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

    private @Nullable ItemResult book(UUID runId, String key, ChainReconciler.Evidence evidence) {
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
        repository.insertItem(
                runId,
                p.id(),
                outcome.status(),
                txHash,
                primary,
                primary != null && primary.adjusted() ? adjustmentId : null);
        meters.counter(
                        "saiman.ledger.reconciliation.items",
                        "status",
                        outcome.status().name())
                .increment();
        boolean wasUnknown = p.chainState() == ChainState.UNKNOWN;
        return new ItemResult(
                outcome.status(),
                wasUnknown && outcome.next().chainState() == ChainState.USED,
                wasUnknown && outcome.next().chainState() == ChainState.UNUSED,
                false);
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
        return UUID.nameUUIDFromBytes(
                ("saiman-ledger:mismatch:" + paymentId + ":" + finding.kind()).getBytes(StandardCharsets.UTF_8));
    }

    /** The single-runner lock: an advisory lock held by one dedicated connection until closed. */
    private final class Lease implements AutoCloseable {

        private final Connection connection;

        Lease(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            try (connection) {
                try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, LOCK_KEY);
                    unlock.execute();
                }
            } catch (SQLException e) {
                // Closing returns a pooled connection that may still hold the session lock, blocking every future
                // run. Abort it instead: the pool evicts an aborted connection and Postgres ends the session,
                // which releases the lock.
                log.error(
                        "Could not release the reconciliation lock; aborting its connection ({})",
                        e.getClass().getName());
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException abortFailed) {
                    log.error(
                            "Could not abort the reconciliation lock connection ({})",
                            abortFailed.getClass().getName());
                }
            } finally {
                running.set(false);
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
