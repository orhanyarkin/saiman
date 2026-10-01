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
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void theLogWindowEndsNearValidBeforeAndNeverAboveTheSafeBlock() {
        ChainBlock safe = new ChainBlock(10_000, 1_000_000);
        long[] window = HeldPaymentResolver.logWindow(1_000_000 - 600, safe, 300); // 600 s = 300 blocks ago
        assertThat(window).containsExactly(10_000 - 300 - 300, 10_000 - 300 + HeldPaymentResolver.SLACK_BLOCKS);
        assertThat(HeldPaymentResolver.logWindow(999_999, safe, 300)[1]).isEqualTo(10_000);
    }

    /** A real HELD intent: signed and sent, then the seller fails, so the outcome is unknown. */
    private UUID heldIntent(UUID run) {
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
    }

    /** Answers as scripted; {@code used == null} or {@code down} throw {@link ChainUnavailableException}. */
    static final class ScriptedChain implements BaseSepoliaUsdc {
        volatile ChainBlock safe = new ChainBlock(1, 1);
        volatile @Nullable Boolean used;
        volatile Optional<String> tx = Optional.empty();
        volatile boolean down;
        volatile boolean logsDown;
        final List<Long> stateBlocks = new CopyOnWriteArrayList<>();
        final List<long[]> logRanges = new CopyOnWriteArrayList<>();

        void reset() {
            safe = new ChainBlock(1, 1);
            used = null;
            tx = Optional.empty();
            down = false;
            logsDown = false;
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
            assertThat(tag).isEqualTo(BlockTag.SAFE);
            return safe;
        }

        @Override
        public boolean authorizationState(String authorizer, String nonce, long blockNumber) {
            Boolean answer = used;
            if (down || answer == null) {
                throw new ChainUnavailableException("down");
            }
            stateBlocks.add(blockNumber);
            return answer;
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
