package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.evmrpc.BaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.BlockTag;
import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.ChainUnavailableException;
import io.github.orhanyarkin.saiman.orchestrator.payment.IntentAuthorization;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentView;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves HELD intents from chain facts (ADR-0018, ADR-0013 amendment). A HELD intent was signed and its
 * outcome is unknown, so its amount keeps counting in the reserved counters (fail closed). Once the {@code safe}
 * block's timestamp is past the authorization's {@code validBefore}, its {@code authorizationState} at that block
 * is final:
 *
 * <ul>
 *   <li>{@code true} (used): HELD -> SETTLED, reserved -> committed on the run and the reservation's day, the tx
 *       hash from an {@code AuthorizationUsed} log search around {@code validBefore} (null if none is found),
 *       {@code PaymentSettled} (BUYER, CHAIN);
 *   <li>{@code false} (expired unused): HELD -> RELEASED, reserved released, {@code PaymentFailed} (FINAL,
 *       {@code expired_unused}).
 * </ul>
 *
 * Not yet expired, or any {@link ChainUnavailableException}: untouched until a later pass.
 *
 * <p>HELD intents with no recorded authorization (RESERVED -> HELD after a failure: the signature provably
 * never left the process) are released locally without a chain read ({@code resolved_by = LOCAL}, no payment
 * event, nothing was authorized).
 *
 * <p>Concurrency: no transaction is open while the RPC is read. One runner at a time across instances: the pass
 * holds a session advisory lock on a dedicated connection and skips if another holds it; the lock is released
 * in {@code finally}, and the connection is aborted if the unlock fails. Each intent is then resolved in its own
 * short transaction under the usual lock order (payment_intent -> run -> spend_day) after re-checking, under
 * {@code FOR UPDATE}, that it is still HELD, so a pass is idempotent. The work lists are ordered by {@code
 * resolution_attempted_at} (never attempted first), stamped on every attempt, so intents that keep failing
 * cannot starve the ones behind them.
 *
 * <p>"Past {@code validBefore}" means before both the safe block's timestamp and the local clock; a safe block more
 * than {@value #MAX_SAFE_AHEAD_SECONDS} s ahead of the local clock skips the chain part of the pass.
 *
 * <p>Runs only when a {@link BaseSepoliaUsdc} client exists ({@code saiman.chain.rpc-url} set); the orchestrator
 * consumes nothing from Kafka for this (a forged "unused" message must not free budget).
 */
@Component
public class HeldPaymentResolver {

    private static final Logger LOG = LoggerFactory.getLogger(HeldPaymentResolver.class);
    private static final String METRIC = "saiman.spend.held_resolution";
    /** Postgres session advisory-lock key of the resolver ("SAIMAN" + 0x0002). */
    private static final long RESOLVER_LOCK = 0x5341494D414E0002L;
    /** Base Sepolia block time. */
    static final long BLOCK_SECONDS = 2;
    /** Blocks searched after the estimated {@code validBefore} block (the estimate can be a little off). */
    static final long SLACK_BLOCKS = 30;
    /** A safe block further ahead of the local clock than this is not trusted: the pass is skipped. */
    static final long MAX_SAFE_AHEAD_SECONDS = 60;

    private final ObjectProvider<BaseSepoliaUsdc> chain;
    private final HeldResolutionProperties properties;
    private final DataSource dataSource;
    private final TransactionTemplate perIntent;
    private final PaymentIntentService intents;
    private final BudgetSpendGuard guard;
    private final MeterRegistry meters;
    private final ObjectProvider<Clock> clock;

    HeldPaymentResolver(
            ObjectProvider<BaseSepoliaUsdc> chain,
            HeldResolutionProperties properties,
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            PaymentIntentService intents,
            BudgetSpendGuard guard,
            MeterRegistry meters,
            ObjectProvider<Clock> clock) {
        this.chain = chain;
        this.properties = properties;
        this.dataSource = dataSource;
        this.perIntent = new TransactionTemplate(transactionManager);
        this.perIntent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.intents = intents;
        this.guard = guard;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${saiman.orchestrator.held-resolution.interval:2m}",
            initialDelayString = "${saiman.orchestrator.held-resolution.interval:2m}")
    void scheduled() {
        if (chain.getIfAvailable() != null) {
            resolveDue();
        }
    }

    /** One pass; returns what it did (an empty pass if there is no chain client or another runner is active). */
    public Pass resolveDue() {
        BaseSepoliaUsdc usdc = chain.getIfAvailable();
        if (usdc == null) {
            return Pass.EMPTY;
        }
        Connection lease;
        try {
            lease = dataSource.getConnection();
        } catch (SQLException e) {
            LOG.warn("HELD resolution skipped: no database connection for the runner lock");
            return Pass.EMPTY;
        }
        boolean locked = false;
        try {
            locked = lockLease(lease);
            return locked ? resolveLocked(usdc) : Pass.EMPTY;
        } catch (SQLException e) {
            LOG.warn("HELD resolution skipped: the runner lock failed");
            return Pass.EMPTY;
        } finally {
            releaseLease(lease, locked);
        }
    }

    /** Session-level advisory lock on a dedicated connection: held across the RPC reads, no transaction open. */
    private static boolean lockLease(Connection lease) throws SQLException {
        try (PreparedStatement st = lease.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            st.setLong(1, RESOLVER_LOCK);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Unlocks and returns the connection. If the unlock fails or says the lock was not held, the connection is
     * aborted instead of going back to the pool with a session lock possibly still on it.
     */
    private static void releaseLease(Connection lease, boolean locked) {
        try {
            if (locked) {
                boolean unlocked = false;
                try (PreparedStatement st = lease.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    st.setLong(1, RESOLVER_LOCK);
                    try (ResultSet rs = st.executeQuery()) {
                        unlocked = rs.next() && rs.getBoolean(1);
                    }
                } catch (SQLException e) {
                    LOG.warn("HELD resolver unlock failed; aborting its connection");
                }
                if (!unlocked) {
                    try {
                        lease.abort(Runnable::run);
                    } catch (SQLException | RuntimeException e) {
                        LOG.warn("aborting the HELD resolver connection failed");
                    }
                }
            }
        } finally {
            try {
                lease.close();
            } catch (SQLException | RuntimeException e) {
                // already aborted or broken: the pool evicts it
            }
        }
    }

    private Pass resolveLocked(BaseSepoliaUsdc usdc) {
        int settled = 0;
        int released = 0;
        int skipped = 0;
        // Never signed: nothing can be on chain, so no chain read is needed (an RPC outage doesn't block it).
        for (UUID id : intents.claimHeldWithoutAuthorization(properties.batchSize())) {
            String outcome = guarded(id, () -> releaseUnsigned(id));
            count(outcome);
            if ("released_local".equals(outcome)) {
                released++;
            } else {
                skipped++;
            }
        }
        ChainBlock safe;
        try {
            safe = usdc.block(BlockTag.SAFE);
        } catch (ChainUnavailableException e) {
            count("chain_unavailable");
            LOG.info("HELD resolution skipped: the chain RPC is unavailable");
            return new Pass(settled, released, skipped);
        }
        long now = clock.getIfAvailable(Clock::systemUTC).instant().getEpochSecond();
        if (safe.timestamp() > now + MAX_SAFE_AHEAD_SECONDS) {
            // A safe block from the future is a lying or broken RPC; trusting it would release live authorizations.
            count("safe_in_future");
            LOG.warn(
                    "HELD resolution skipped: the safe block is {} s ahead of the local clock", safe.timestamp() - now);
            return new Pass(settled, released, skipped);
        }
        // Expired by both the chain's and the local clock: neither a fast RPC nor a fast local clock alone can
        // make an authorization final early.
        long expiredBefore = Math.min(safe.timestamp(), now);
        List<UUID> due = intents.claimHeldExpiredBefore(expiredBefore, properties.batchSize());
        for (UUID id : due) {
            String outcome = guarded(id, () -> resolveOne(usdc, id, safe, expiredBefore));
            count(outcome);
            switch (outcome) {
                case "settled" -> settled++;
                case "released" -> released++;
                default -> skipped++;
            }
        }
        if (!due.isEmpty()) {
            LOG.info(
                    "HELD resolution at safe block {}: {} settled, {} released, {} skipped",
                    safe.number(),
                    settled,
                    released,
                    skipped);
        }
        return new Pass(settled, released, skipped);
    }

    private String guarded(UUID id, Supplier<String> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            LOG.error("HELD resolution of payment intent {} failed; left HELD", id, e);
            return "error";
        }
    }

    private String releaseUnsigned(UUID id) {
        return Objects.requireNonNull(perIntent.execute(status -> {
            PaymentIntentView locked = intents.lockById(id).orElse(null);
            if (locked == null || !guard.releaseHeldUnsignedLocked(locked)) {
                return "already_resolved";
            }
            return "released_local";
        }));
    }

    private String resolveOne(BaseSepoliaUsdc usdc, UUID id, ChainBlock safe, long expiredBefore) {
        // Reads first, outside any transaction: no row lock or pooled transaction across RPC latency.
        IntentAuthorization held = intents.readAuthorization(id).orElse(null);
        if (held == null || held.status() != PaymentIntentStatus.HELD) {
            return "already_resolved";
        }
        if (held.validBefore() >= expiredBefore) {
            return "not_expired";
        }
        boolean used;
        @Nullable String txHash = null;
        try {
            used = usdc.authorizationState(held.payer(), held.nonce(), safe.number());
            if (used) {
                long[] window = logWindow(held.validBefore(), safe, properties.logWindowBlocks());
                txHash = usdc.findAuthorizationTx(held.payer(), held.nonce(), window[0], window[1])
                        .orElse(null);
            }
        } catch (ChainUnavailableException e) {
            return "chain_unavailable";
        }
        boolean finalUsed = used;
        @Nullable String finalTx = txHash;
        return Objects.requireNonNull(perIntent.execute(status -> {
            IntentAuthorization locked = intents.lockAuthorization(id).orElse(null);
            if (locked == null || locked.status() != PaymentIntentStatus.HELD) {
                return "already_resolved";
            }
            if (finalUsed) {
                guard.settleHeldLocked(locked, finalTx);
                return "settled";
            }
            guard.releaseHeldLocked(locked);
            return "released";
        }));
    }

    /**
     * The block range searched for the {@code AuthorizationUsed} log: the authorization can only have been used
     * before {@code validBefore}, so from {@code windowBlocks} before the block estimated at {@code validBefore}
     * to a little after it, never above the safe block.
     */
    static long[] logWindow(long validBefore, ChainBlock safe, int windowBlocks) {
        long secondsBack = Math.max(0, safe.timestamp() - validBefore);
        long estimate = safe.number() - (secondsBack + BLOCK_SECONDS - 1) / BLOCK_SECONDS;
        long to = Math.min(safe.number(), estimate + SLACK_BLOCKS);
        long from = Math.max(0, estimate - windowBlocks);
        return new long[] {from, Math.max(from, to)};
    }

    private void count(String outcome) {
        meters.counter(METRIC, "outcome", outcome).increment();
    }

    /** What one pass changed. */
    public record Pass(int settled, int released, int skipped) {
        static final Pass EMPTY = new Pass(0, 0, 0);
    }
}
