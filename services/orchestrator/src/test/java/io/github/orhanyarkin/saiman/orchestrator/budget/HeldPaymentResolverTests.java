package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evmrpc.BaseSepoliaUsdc;
import io.github.orhanyarkin.saiman.evmrpc.BlockTag;
import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.ChainUnavailableException;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.orchestrator.outbox.OutboxTestAccess;
import io.github.orhanyarkin.saiman.orchestrator.outbox.OutboxTestAccess.Publication;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaidCallException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentTestAccess;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * HELD resolution from chain facts (ADR-0018), against a scripted {@link BaseSepoliaUsdc} (hermetic: no RPC).
 * Each HELD intent is made by a real paid call whose signed retry fails (the seller answers 500 after signing),
 * so payer, nonce, validBefore and the reserved counters are the ones the spend guard recorded.
 */
class HeldPaymentResolverTests extends SpendTestSupport {

    private static final String TX = "0x" + "cd".repeat(32);
    private static final long AMOUNT = 10_000;

    @Autowired
    private HeldPaymentResolver resolver;

    @Autowired
    private ScriptedChain chain;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void resetChain() {
        chain.reset();
    }

    @Test
    void usedOnChainSettlesMovesTheCountersAndPublishesChainEvidence() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safeAfter(validBefore(held));
        chain.used = true;
        chain.tx = Optional.of(TX);

        HeldPaymentResolver.Pass pass = resolver.resolveDue();

        assertThat(pass).isEqualTo(new HeldPaymentResolver.Pass(1, 0, 0));
        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
        assertThat(intents.find(held).orElseThrow().txHash()).isEqualTo(TX);
        assertThat(resolvedBy(held)).isEqualTo("CHAIN");
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, AMOUNT));
        assertThat(today()).isEqualTo(new RunCounters(0, 0, AMOUNT));
        // The state read is at the safe block, and the log search ends at or below it.
        assertThat(chain.stateBlocks).containsExactly(chain.safe.number());
        assertThat(chain.logRanges).singleElement().satisfies(range -> {
            assertThat(range[1]).isLessThanOrEqualTo(chain.safe.number());
            assertThat(range[1] - range[0]).isLessThan(1000);
        });
        List<Publication> settled = OutboxTestAccess.publications(jdbc, "PaymentSettled");
        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).event().at("/evidence").asString()).isEqualTo("CHAIN");
        assertThat(settled.get(0).event().at("/txHash").asString()).isEqualTo(TX);
        assertThat(settled.get(0).event().at("/book").asString()).isEqualTo("BUYER");

        // Idempotent: nothing is HELD any more.
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, AMOUNT));
    }

    @Test
    void usedWithoutAFoundTransactionSettlesWithANullTxHash() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safeAfter(validBefore(held));
        chain.used = true;
        chain.tx = Optional.empty();

        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(1, 0, 0));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
        assertThat(intents.find(held).orElseThrow().txHash()).isNull();
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, AMOUNT));
        Publication settled =
                OutboxTestAccess.publications(jdbc, "PaymentSettled").get(0);
        assertThat(settled.event().at("/txHash").isNull()).isTrue();
        assertThat(settled.event().at("/evidence").asString()).isEqualTo("CHAIN");
    }

    @Test
    void unusedOnChainReleasesTheCountersAndPublishesAFinalFailure() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safeAfter(validBefore(held));
        chain.used = false;

        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 1, 0));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(resolvedBy(held)).isEqualTo("CHAIN");
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 0));
        assertThat(chain.logRanges).isEmpty();
        List<Publication> failed = OutboxTestAccess.publications(jdbc, "PaymentFailed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).event().at("/finality").asString()).isEqualTo("FINAL");
        assertThat(failed.get(0).event().at("/reasonCode").asString()).isEqualTo("expired_unused");
        assertThat(failed.get(0).event().at("/book").asString()).isEqualTo("BUYER");
    }

    @Test
    void beforeValidBeforeAtTheSafeBlockNothingChanges() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safe = new ChainBlock(1_000_000, validBefore(held) - 1);
        chain.used = false;

        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, AMOUNT, 0));
        assertThat(chain.stateBlocks).isEmpty();
    }

    @Test
    void expiredAtTheSafeBlockButNotYetByTheLocalClockNothingChanges() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safe = new ChainBlock(1_000_000, validBefore(held) + 20);
        chain.clockAheadOfSafe = -30; // the safe block is 30 s ahead: tolerated, but now < validBefore
        chain.used = false;
        try {
            assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));
        } finally {
            chain.clockAheadOfSafe = 0;
        }

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, AMOUNT, 0));
        assertThat(chain.stateBlocks).isEmpty();
    }

    @Test
    void aSafeBlockFarAheadOfTheLocalClockSkipsThePass() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safeAfter(validBefore(held));
        chain.clockAheadOfSafe = -(HeldPaymentResolver.MAX_SAFE_AHEAD_SECONDS + 1);
        chain.used = false;
        try {
            assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));
        } finally {
            chain.clockAheadOfSafe = 0;
        }
        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(chain.stateBlocks).isEmpty();

        assertThat(resolver.resolveDue())
                .as("with the clocks in step again, it resolves")
                .isEqualTo(new HeldPaymentResolver.Pass(0, 1, 0));
    }

    @Test
    void anUnavailableChainLeavesTheIntentHeldAndCounted() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);

        chain.down = true; // the safe block itself can't be read
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));

        chain.down = false;
        chain.safeAfter(validBefore(held));
        chain.used = null; // authorizationState fails
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 1));

        chain.used = true;
        chain.logsDown = true; // the state answers, the log lookup fails: retried later, not settled blind
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 1));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, AMOUNT, 0));
        assertThat(today()).isEqualTo(new RunCounters(0, AMOUNT, 0));
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentSettled")).isEmpty();
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentFailed")).isEmpty();
    }

    @Test
    void safeBlockExactlyAtValidBeforeLeavesTheIntentHeld() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safe = new ChainBlock(5_000_000, validBefore(held));
        chain.used = false;

        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(chain.stateBlocks).isEmpty();
    }

    @Test
    void localClockExactlyAtValidBeforeLeavesTheIntentHeld() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safe = new ChainBlock(5_000_000, validBefore(held) + 20);
        chain.clockAheadOfSafe = -20; // now == validBefore
        chain.used = false;

        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(chain.stateBlocks).isEmpty();
    }

    @Test
    void safeBlockExactlyTheToleranceAheadOfTheLocalClockStillResolves() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        double skippedBefore = outcomes("safe_in_future");
        chain.safeAfter(validBefore(held));
        chain.clockAheadOfSafe = -HeldPaymentResolver.MAX_SAFE_AHEAD_SECONDS; // S == now + 60
        chain.used = false;

        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 1, 0));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(outcomes("safe_in_future")).isEqualTo(skippedBefore);
    }

    @Test
    void aWrongChainIdLeavesTheIntentHeldAndReleasesTheRunnerLock() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safeAfter(validBefore(held));
        chain.used = false;
        double wrongBefore = outcomes(HeldPaymentResolver.WRONG_CHAIN);

        chain.wrongChainOnBlock = true; // the deferred eth_chainId check fails on the first call of the pass
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));
        chain.wrongChainOnBlock = false;
        chain.wrongChainOnState = true; // or on the per-intent read
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 1));

        assertThat(outcomes(HeldPaymentResolver.WRONG_CHAIN)).isEqualTo(wrongBefore + 2);
        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, AMOUNT, 0));
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentFailed")).isEmpty();

        chain.wrongChainOnState = false;
        assertThat(resolver.resolveDue())
                .as("the runner lock was released: the next pass works")
                .isEqualTo(new HeldPaymentResolver.Pass(0, 1, 0));
    }

    @Test
    void aUsedAuthorizationWhoseLogLookupFailsStaysHeldAndIsNeverReleased() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        chain.safeAfter(validBefore(held));
        chain.used = true;
        chain.logsDown = true;
        double failedBefore = outcomes("used_tx_lookup_failed");

        for (int pass = 0; pass < 3; pass++) {
            assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 1));
        }

        assertThat(outcomes("used_tx_lookup_failed")).isEqualTo(failedBefore + 3);
        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, AMOUNT, 0));
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentFailed")).isEmpty();
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentSettled")).isEmpty();

        chain.logsDown = false;
        chain.tx = Optional.of(TX);
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(1, 0, 0));
        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
    }

    private double outcomes(String outcome) {
        Counter counter = meters.find("saiman.spend.held_resolution")
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theLogWindowEndsNearValidBeforeAndNeverAboveTheSafeBlock() {
        ChainBlock safe = new ChainBlock(10_000, 1_000_000);
        long[] window = HeldPaymentResolver.logWindow(1_000_000 - 600, safe, 300); // 600 s = 300 blocks ago
        assertThat(window).containsExactly(10_000 - 300 - 300, 10_000 - 300 + HeldPaymentResolver.SLACK_BLOCKS);
        assertThat(HeldPaymentResolver.logWindow(999_999, safe, 300)[1]).isEqualTo(10_000);
    }

    @Test
    void aHeldIntentThatNeverSignedIsReleasedLocallyWithoutAChainReadOrAnEvent() {
        UUID run = createRun(50_000);
        UUID held = heldIntent(run);
        // The shape RESERVED -> HELD leaves: no authorization recorded.
        jdbc.sql("UPDATE payment_intent SET auth_nonce = NULL, payer = NULL, valid_before = NULL WHERE id = :id")
                .param("id", held)
                .update();
        chain.down = true; // a chain outage must not block it

        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 1, 0));

        assertThat(intents.find(held).orElseThrow().status()).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(resolvedBy(held)).isEqualTo("LOCAL");
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 0));
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentSettled")).isEmpty();
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentFailed")).isEmpty();
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
    }

    @Test
    void intentsThatKeepFailingDoNotBlockTheOnesBehindThem() {
        int batch = 20;
        UUID lastRun = null;
        UUID last = null;
        long base = 0;
        for (int i = 0; i <= batch; i++) {
            UUID run = createRun(50_000);
            UUID held = heldIntent(run);
            if (i == 0) {
                base = validBefore(held);
            }
            jdbc.sql("UPDATE payment_intent SET valid_before = :vb WHERE id = :id")
                    .param("vb", base + i)
                    .param("id", held)
                    .update();
            if (i < batch) {
                chain.failingNonces.add(nonce(held));
            } else {
                last = held;
                lastRun = run;
            }
        }
        chain.safeAfter(base + batch);
        chain.used = true;

        // The first pass examines the 20 earliest (all failing); the 21st is untouched.
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, batch));
        assertThat(intents.find(last).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);

        // The second pass tries the never-attempted one first, ahead of the 20 that just failed.
        assertThat(resolver.resolveDue().settled()).isEqualTo(1);
        assertThat(intents.find(last).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
        assertThat(run(lastRun)).isEqualTo(new RunCounters(50_000, 0, AMOUNT));
    }

    @Test
    void twoRacingPassesAndAConcurrentCommitLeaveExactCountersAndOneEventPerKind() throws Exception {
        UUID run = createRun(50_000);
        UUID unusedRun = createRun(50_000);
        UUID usedIntent = heldIntent(run);
        UUID unusedIntent = heldIntent(unusedRun);
        chain.safeAfter(Math.max(validBefore(usedIntent), validBefore(unusedIntent)));
        chain.usedByNonce.put(nonce(usedIntent), true);
        chain.usedByNonce.put(nonce(unusedIntent), false);
        chain.delayMillis = 300; // keep the passes overlapping

        PaymentIntentHandle fresh = newIntent(run);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<HeldPaymentResolver.Pass> a = pool.submit(() -> {
                start.await();
                return resolver.resolveDue();
            });
            Future<HeldPaymentResolver.Pass> b = pool.submit(() -> {
                start.await();
                return resolver.resolveDue();
            });
            Future<?> commit = pool.submit(() -> {
                start.await();
                return client.send(fresh, null);
            });
            start.countDown();
            HeldPaymentResolver.Pass first = a.get(30, TimeUnit.SECONDS);
            HeldPaymentResolver.Pass second = b.get(30, TimeUnit.SECONDS);
            commit.get(30, TimeUnit.SECONDS);
            // One runner did the work; the other found the lock taken or nothing left.
            assertThat(first.settled() + second.settled()).isEqualTo(1);
            assertThat(first.released() + second.released()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 2 * AMOUNT));
        assertThat(run(unusedRun)).isEqualTo(new RunCounters(50_000, 0, 0));
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 2 * AMOUNT));
        assertThat(intents.find(usedIntent).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
        assertThat(intents.find(unusedIntent).orElseThrow().status()).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentSettled")).hasSize(2); // resolved + the fresh call
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentFailed")).hasSize(1);
        // The runner lock is free again.
        chain.delayMillis = 0;
        assertThat(resolver.resolveDue()).isEqualTo(new HeldPaymentResolver.Pass(0, 0, 0));
    }

    private String nonce(UUID intent) {
        return jdbc.sql("SELECT auth_nonce FROM payment_intent WHERE id = :id")
                .param("id", intent)
                .query(String.class)
                .single();
    }

    /** A real HELD intent: signed and sent, then the seller fails, so the outcome is unknown. */
    private UUID heldIntent(UUID run) {
        PaymentTestAccess.resetCircuitBreaker(client); // many failures in a row must not open the breaker
        PaymentIntentHandle handle = newIntent(run);
        seller.paidMode(FakeSeller.PaidMode.FAIL_500);
        try {
            client.send(handle, null);
        } catch (PaidCallException expected) {
            // outcome unknown
        }
        seller.paidMode(FakeSeller.PaidMode.SETTLE);
        if (intents.find(handle.id()).orElseThrow().status() != PaymentIntentStatus.HELD) {
            // A declined-after-signing answer may leave it SIGNED for the caller; hold it as recovery would.
            intents.markHeld(handle.id());
        }
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, AMOUNT, 0));
        return handle.id();
    }

    private long validBefore(UUID intent) {
        return jdbc.sql("SELECT valid_before FROM payment_intent WHERE id = :id")
                .param("id", intent)
                .query(Long.class)
                .single();
    }

    private String resolvedBy(UUID intent) {
        Map<String, Object> row = jdbc.sql("SELECT resolved_by, resolved_at FROM payment_intent WHERE id = :id")
                .param("id", intent)
                .query()
                .singleRow();
        assertThat(row.get("resolved_at")).isNotNull();
        return (String) row.get("resolved_by");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ChainConfiguration {

        @Bean
        ScriptedChain scriptedChain() {
            return new ScriptedChain();
        }

        /** The resolver's local clock: the scripted safe block's time plus {@code clockAheadOfSafe} seconds. */
        @Bean
        Clock resolverClock(ScriptedChain chain) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return Instant.ofEpochSecond(chain.safe.timestamp() + chain.clockAheadOfSafe);
                }
            };
        }
    }

    /** Answers as scripted; {@code used == null} or {@code down} throw {@link ChainUnavailableException}. */
    static final class ScriptedChain implements BaseSepoliaUsdc {
        volatile ChainBlock safe = new ChainBlock(1, 1);
        /** Seconds the local clock is ahead of the safe block (negative: the safe block is ahead). */
        volatile long clockAheadOfSafe;

        volatile @Nullable Boolean used;
        volatile Optional<String> tx = Optional.empty();
        volatile boolean down;
        volatile boolean logsDown;
        /** Throw what the real client's deferred chain-id check throws for an RPC that is not Base Sepolia. */
        volatile boolean wrongChainOnBlock;

        volatile boolean wrongChainOnState;
        volatile long delayMillis;
        final Map<String, Boolean> usedByNonce = new ConcurrentHashMap<>();
        final Set<String> failingNonces = ConcurrentHashMap.newKeySet();
        final List<Long> stateBlocks = new CopyOnWriteArrayList<>();
        final List<long[]> logRanges = new CopyOnWriteArrayList<>();

        void reset() {
            safe = new ChainBlock(1, 1);
            used = null;
            tx = Optional.empty();
            down = false;
            logsDown = false;
            wrongChainOnBlock = false;
            wrongChainOnState = false;
            clockAheadOfSafe = 0;
            delayMillis = 0;
            usedByNonce.clear();
            failingNonces.clear();
            stateBlocks.clear();
            logRanges.clear();
        }

        /** A safe block 10 minutes past {@code validBefore}. */
        void safeAfter(long validBefore) {
            safe = new ChainBlock(5_000_000, validBefore + 600);
        }

        @Override
        public ChainBlock block(BlockTag tag) {
            if (down) {
                throw new ChainUnavailableException("down");
            }
            if (wrongChainOnBlock) {
                throw wrongChain();
            }
            assertThat(tag).isEqualTo(BlockTag.SAFE);
            return safe;
        }

        @Override
        public boolean authorizationState(String authorizer, String nonce, long blockNumber) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (wrongChainOnState) {
                throw wrongChain();
            }
            Boolean answer = usedByNonce.getOrDefault(nonce, used);
            if (down || answer == null || failingNonces.contains(nonce)) {
                throw new ChainUnavailableException("down");
            }
            stateBlocks.add(blockNumber);
            return answer;
        }

        private static IllegalStateException wrongChain() {
            return new IllegalStateException("saiman.chain.rpc-url is not Base Sepolia (eth_chainId != 84532)");
        }

        @Override
        public Optional<UsdcReceipt> receipt(String txHash) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findAuthorizationTx(String authorizer, String nonce, long fromBlock, long toBlock) {
            if (down || logsDown) {
                throw new ChainUnavailableException("down");
            }
            logRanges.add(new long[] {fromBlock, toBlock});
            return tx;
        }
    }
}
