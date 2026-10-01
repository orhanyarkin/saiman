package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReconciliationRepository#duePaymentKeys} order under a flood of forged with-tx seller facts. Each test runs
 * in one rolled-back transaction (the repository joins it), so the flood never reaches the other reconciliation
 * tests; rows those tests committed are counted in, not assumed away.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = "saiman.test.context=reconciliation")
@Transactional
class DueOrderTests {

    private static final long SAFE_TS = 1_800_000_000L;
    private static final int BATCH = 50;
    private static final int TX_SHARE = BATCH / 2;

    @Autowired
    private ReconciliationRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private final SplittableRandom random = new SplittableRandom(Pbt.seedOf(2_000_000));

    /**
     * 200 never-checked forged seller facts with a bogus tx cannot push a real (buyer-corroborated) payment that was
     * checked once out of the next runs: it comes back as soon as the real payments checked before it have had
     * their turn, while the old "never checked first" order needed 200 / 25 = 8 runs.
     */
    @Test
    void realPaymentCheckedOnceIsRecheckedBeforeAFloodOfForgedSellerFacts() {
        String real = insert("SETTLED", "SETTLED", true);
        jdbc.sql("UPDATE payment SET last_checked_at = clock_timestamp() WHERE payment_key = :key")
                .param("key", real)
                .update();
        for (int i = 0; i < 200; i++) {
            insert("NONE", "SETTLED", false);
        }
        long checkedBefore = jdbc.sql("""
                        SELECT count(*) FROM payment
                         WHERE (buyer_tx_hash IS NOT NULL OR seller_tx_hash IS NOT NULL) AND buyer_state <> 'NONE'
                           AND (last_checked_at IS NULL OR last_checked_at < (SELECT last_checked_at FROM payment
                                                                                WHERE payment_key = :key))
                        """).param("key", real).query(Long.class).single();
        int allowedRuns = (int) (checkedBefore / TX_SHARE) + 1;

        int run = 1;
        while (!simulateRun().contains(real)) {
            run++;
            assertThat(run)
                    .as("re-checked within %d runs (%d real payments were due before it)", allowedRuns, checkedBefore)
                    .isLessThanOrEqualTo(allowedRuns);
        }
        assertThat(run).isLessThan(200 / TX_SHARE);
    }

    @Test
    void suspectPaymentQueuesBehindACleanOneCheckedLater() {
        String suspect = insert("SETTLED", "SETTLED", true);
        String clean = insert("SETTLED", "SETTLED", true);
        jdbc.sql("UPDATE payment SET last_checked_at = clock_timestamp() - interval '2 hours' WHERE payment_key = :k")
                .param("k", suspect)
                .update();
        jdbc.sql("UPDATE payment SET last_checked_at = clock_timestamp() - interval '1 hour' WHERE payment_key = :k")
                .param("k", clean)
                .update();
        jdbc.sql("""
                        INSERT INTO reconciliation_mismatch (id, payment_id, kind)
                        SELECT gen_random_uuid(), id, 'TX_NOT_FOUND' FROM payment WHERE payment_key = :k
                        """).param("k", suspect).update();

        List<String> order = jdbc.sql("""
                        SELECT payment_key FROM payment p WHERE payment_key IN (:keys) ORDER BY %s
                        """.formatted(ReconciliationRepository.DUE_ORDER))
                .param("keys", List.of(suspect, clean))
                .query(String.class)
                .list();

        assertThat(order).containsExactly(clean, suspect);
    }

    /** One run's selection; every selected payment is stamped as checked, as a run does. */
    private List<String> simulateRun() {
        List<String> due = repository.duePaymentKeys(SAFE_TS, 0, BATCH);
        jdbc.sql("UPDATE payment SET last_checked_at = clock_timestamp() WHERE payment_key IN (:keys)")
                .param("keys", due)
                .update();
        return due;
    }

    /** A payment row with a reported tx on the given side(s); returns its key. */
    private String insert(String buyerState, String sellerState, boolean buyerTx) {
        String payer = hex(20);
        String nonce = hex(32);
        String key = "eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:" + payer + ":" + nonce;
        jdbc.sql("""
                        INSERT INTO payment (id, payment_key, network, asset_address, payer, nonce, pay_to,
                                             amount_atomic, asset, decimals, valid_before, buyer_state, seller_state,
                                             buyer_tx_hash, seller_tx_hash)
                        VALUES (:id, :key, 'eip155:84532', '0x036cbd53842c5426634e7929541ec2318f3dcf7e', :payer,
                                :nonce, :payTo, 20000, 'USDC', 6, 1790000060, :buyerState, :sellerState,
                                :buyerTx, :sellerTx)
                        """)
                .param("id", UUID.randomUUID())
                .param("key", key)
                .param("payer", payer)
                .param("nonce", nonce)
                .param("payTo", hex(20))
                .param("buyerState", buyerState)
                .param("sellerState", sellerState)
                .param("buyerTx", buyerTx ? hex(32) : null)
                .param("sellerTx", hex(32))
                .update();
        return key;
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            b[i] = (byte) random.nextInt(256);
        }
        return "0x" + HexFormat.of().formatHex(b);
    }
}
