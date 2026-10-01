package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.evmrpc.BaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.BlockTag;
import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.ChainUnavailableException;
import io.github.orhanyarkin.saiman.orchestrator.payment.IntentAuthorization;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * Not yet expired, or any {@link ChainUnavailableException}: untouched until a later pass. Chain reads happen
 * before any row lock; each intent is then resolved in its own transaction under the usual lock order
 * (payment_intent -> run -> spend_day) after re-checking it is still HELD, so a pass is idempotent. One runner at
 * a time across instances: the pass holds a transaction-scoped advisory lock and skips if another holds it.
 *
 * <p>Runs only when a {@link BaseSepoliaUsdc} client exists ({@code saiman.chain.rpc-url} set); the orchestrator
 * consumes nothing from Kafka for this (a forged "unused" message must not free budget).
 */
@Component
public class HeldPaymentResolver {

    private static final Logger LOG = LoggerFactory.getLogger(HeldPaymentResolver.class);
    private static final String METRIC = "saiman.spend.held_resolution";
    /** Postgres advisory-lock key of the resolver ("SAIMAN" + 0x0002). */
    private static final long RESOLVER_LOCK = 0x5341494D414E0002L;
    /** Base Sepolia block time. */
    static final long BLOCK_SECONDS = 2;
    /** Blocks searched after the estimated {@code validBefore} block (the estimate can be a little off). */
    static final long SLACK_BLOCKS = 30;

    private final ObjectProvider<BaseSepoliaUsdc> chain;
    private final HeldResolutionProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate pass;
    private final TransactionTemplate perIntent;
    private final PaymentIntentService intents;
    private final BudgetSpendGuard guard;
    private final MeterRegistry meters;

    HeldPaymentResolver(
            ObjectProvider<BaseSepoliaUsdc> chain,
            HeldResolutionProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            PaymentIntentService intents,
            BudgetSpendGuard guard,
            MeterRegistry meters) {
        this.chain = chain;
        this.properties = properties;
        this.jdbc = jdbc;
        this.pass = new TransactionTemplate(transactionManager);
        this.perIntent = new TransactionTemplate(transactionManager);
        this.perIntent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.intents = intents;
        this.guard = guard;
        this.meters = meters;
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
        return Objects.requireNonNull(pass.execute(status -> {
            boolean locked = Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                    .param("key", RESOLVER_LOCK)
                    .query(Boolean.class)
                    .single());
            if (!locked) {
                return Pass.EMPTY;
            }
            return resolveLocked(usdc);
        }));
    }

    private Pass resolveLocked(BaseSepoliaUsdc usdc) {
        ChainBlock safe;
        try {
            safe = usdc.block(BlockTag.SAFE);
        } catch (ChainUnavailableException e) {
            count("chain_unavailable");
            LOG.info("HELD resolution skipped: the chain RPC is unavailable");
            return Pass.EMPTY;
        }
        int withoutAuthorization = intents.countHeldWithoutAuthorization();
        if (withoutAuthorization > 0) {
            LOG.warn(
                    "{} HELD payment intent(s) have no recorded authorization and cannot be resolved on chain;"
                            + " they stay counted",
                    withoutAuthorization);
        }
        List<UUID> due = intents.findHeldExpiredBefore(safe.timestamp(), properties.batchSize());
        int settled = 0;
        int released = 0;
        int skipped = 0;
        for (UUID id : due) {
            String outcome;
            try {
                outcome = resolveOne(usdc, id, safe);
            } catch (RuntimeException e) {
                LOG.error("HELD resolution of payment intent {} failed; left HELD", id, e);
                outcome = "error";
            }
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

    private String resolveOne(BaseSepoliaUsdc usdc, UUID id, ChainBlock safe) {
        IntentAuthorization held = intents.findAuthorization(id).orElse(null);
        if (held == null || held.status() != PaymentIntentStatus.HELD) {
            return "already_resolved";
        }
        if (held.validBefore() >= safe.timestamp()) {
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
