package io.github.orhanyarkin.saiman.ledger.journal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.Superuser;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentBook;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verification 3 of ADR-0029: V9 must not abort over an adjustment no {@code reconciliation_mismatch} row points at.
 * {@code insertMismatch} is {@code ON CONFLICT (payment_id, kind) DO NOTHING}, so the <em>second</em> adjustment for
 * one payment and kind is never linked — the tamper demo plus a reconciliation run, run twice, produces exactly that,
 * and so does a split settlement whose seller event arrives after the first run.
 *
 * <p>Two payments are seeded at V8 (the context stops there, {@code spring.flyway.target}) and one migration to head
 * covers all three code paths for the replacement's id, which the assertions discriminate by id:
 *
 * <ol>
 *   <li>adjustment 1 of payment A: linked from {@code reconciliation_mismatch}, so run 1 names its replacement;
 *   <li>adjustment 2 of payment A: linked only from {@code reconciliation_item}, so run 2 names its replacement;
 *   <li>the adjustment of payment B: in neither table, so the replacement is named after the entry it replaces.
 * </ol>
 */
@LedgerIntegrationTest
@TestPropertySource(properties = {"saiman.test.context=unlinked-adjustment", "spring.flyway.target=8"})
class UnlinkedAdjustmentMigrationTests {

    private static final long AMOUNT = 20_000;
    private static final long TAMPER = 5_000;

    /** scripts/ledger-tamper-demo.sh raises both postings of the newest SALE, so the entry still balances. */
    private static final String TAMPER_SQL = """
            UPDATE ledger.posting p SET amount_atomic = p.amount_atomic + 5000
             WHERE p.entry_id = (SELECT id FROM ledger.journal_entry
                                  WHERE kind = 'SALE' ORDER BY recorded_at DESC, id DESC LIMIT 1)
            """;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private SuperuserDatabase superuserDatabase;

    private static boolean migrated;
    private static TestPayment twice;
    private static TestPayment orphan;
    private static UUID firstRun;
    private static UUID secondRun;
    private static UUID firstAdjustment;
    private static UUID secondAdjustment;
    private static UUID orphanAdjustment;
    private static long twiceWalletBefore;
    private static long orphanWalletBefore;

    /** Seeds the twice-tampered shape and an adjustment in neither table, then migrates to head once. */
    @BeforeEach
    void seedAtV8AndMigrateOnce() throws SQLException {
        if (migrated) {
            return;
        }
        assertThat(headVersion())
                .as("the context stops at V8 so the old rule still applies")
                .isEqualTo("8");

        twice = TestPayment.random(new SplittableRandom(291), AMOUNT);
        ledger.record(PaymentFact.of(twice.sellerSettled()), PaymentTopics.SETTLED);
        tamperTheNewestSale();
        tamperTheNewestSale();
        firstRun = UUID.randomUUID();
        secondRun = UUID.randomUUID();
        firstAdjustment = PaymentBook.entryId(twice.key(), LedgerBook.PLATFORM, "ADJUSTMENT:" + firstRun);
        secondAdjustment = PaymentBook.entryId(twice.key(), LedgerBook.PLATFORM, "ADJUSTMENT:" + secondRun);

        orphan = TestPayment.random(new SplittableRandom(292), AMOUNT);
        ledger.record(PaymentFact.of(orphan.sellerSettled()), PaymentTopics.SETTLED);
        orphanAdjustment = PaymentBook.entryId(orphan.key(), LedgerBook.PLATFORM, "ADJUSTMENT:" + UUID.randomUUID());

        tx.executeWithoutResult(status -> {
            account("platform:suspense:usdc", "PLATFORM", "SUSPENSE", null);
            insertRun(firstRun);
            insertRun(secondRun);
            // Run 1: the finding points at its adjustment, and so does the run's own item row.
            crossBookAdjustment(firstAdjustment, twice);
            insertMismatch(firstRun, twice, firstAdjustment);
            insertItem(firstRun, twice, firstAdjustment);
            // Run 2 posted a second adjustment for the same payment and kind. The mismatch insert conflicted and did
            // nothing, so only the item row knows which run that entry belongs to.
            crossBookAdjustment(secondAdjustment, twice);
            insertItem(secondRun, twice, secondAdjustment);
            // Payment B: an adjustment nothing points at (a run whose rows were never written).
            crossBookAdjustment(orphanAdjustment, orphan);
        });
        twiceWalletBefore = balanceOf(wallet(twice));
        orphanWalletBefore = balanceOf(wallet(orphan));
        assertThat(balanceOf("platform:suspense:usdc")).isEqualTo(3 * TAMPER);

        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target(MigrationVersion.LATEST)
                .load()
                .migrate();
        assertThat(headVersion())
                .as("the migration succeeded, it did not abort over the unlinked rows")
                .isEqualTo("10");
        migrated = true;
    }

    /** Both adjustments of the twice-tampered payment are reversed and replaced in the seller's book. */
    @Test
    void anAdjustmentLinkedOnlyFromTheItemRowIsReplacedUnderItsOwnRunId() {
        UUID fromMismatch = PaymentBook.entryId(twice.key(), LedgerBook.SELLER, "ADJUSTMENT:" + firstRun);
        UUID fromItem = PaymentBook.entryId(twice.key(), LedgerBook.SELLER, "ADJUSTMENT:" + secondRun);

        assertThat(adjustmentIds(twice))
                .as("both originals stay, both replacements carry the id their run derives")
                .containsExactlyInAnyOrder(firstAdjustment, secondAdjustment, fromMismatch, fromItem);
        assertThat(bookOf(fromItem)).isEqualTo("SELLER");
        assertThat(legsOf(fromItem))
                .containsExactlyInAnyOrder("DEBIT " + suspense(twice) + " 5000", "CREDIT " + wallet(twice) + " 5000");
        assertThat(reversalsOf(firstAdjustment)).hasSize(1);
        assertThat(reversalsOf(secondAdjustment)).hasSize(1);
        assertThat(paymentIdOf(fromItem))
                .as("the replacement is about the payment, so reconciliation counts it in the nets")
                .isEqualTo(PaymentProjection.paymentId(twice.key()));
        assertThat(balanceOf(wallet(twice))).isEqualTo(twiceWalletBefore);
        assertThat(balanceOf(suspense(twice)))
                .as("both differences now sit in the seller's own suspense account")
                .isEqualTo(2 * TAMPER);
    }

    /** With neither a mismatch nor an item row, the replacement is named after the entry it replaces. */
    @Test
    void anAdjustmentInNeitherTableIsReplacedUnderTheEntryDerivedId() {
        UUID fallback = UUID.nameUUIDFromBytes(
                ("saiman-ledger:entry:" + orphan.key() + ":SELLER:ADJUSTMENT:replaces:" + orphanAdjustment)
                        .getBytes(UTF_8));

        assertThat(adjustmentIds(orphan)).containsExactlyInAnyOrder(orphanAdjustment, fallback);
        assertThat(bookOf(fallback)).isEqualTo("SELLER");
        assertThat(legsOf(fallback))
                .containsExactlyInAnyOrder("DEBIT " + suspense(orphan) + " 5000", "CREDIT " + wallet(orphan) + " 5000");
        assertThat(reversalsOf(orphanAdjustment)).hasSize(1);
        assertThat(balanceOf(wallet(orphan))).isEqualTo(orphanWalletBefore);
        assertThat(balanceOf(suspense(orphan))).isEqualTo(TAMPER);
    }

    /** The invariants the correction must not trade away, with three corrections in one database. */
    @Test
    void everyBookBalancesAndTheWholeLedgerSumsToZero() {
        assertThat(unbalancedBooks()).as("every book balances per asset").isEmpty();
        assertThat(wholeLedgerSum()).as("the whole-ledger sum stays zero").isZero();
        assertThat(balanceOf("platform:suspense:usdc"))
                .as("the retired platform pot nets to zero")
                .isZero();
    }

    private void insertRun(UUID runId) {
        jdbc.sql("""
                        INSERT INTO reconciliation_run (id, started_at, status, network)
                        VALUES (:id, :at, 'COMPLETED', 'eip155:84532')
                        """)
                .param("id", runId)
                .param("at", Timestamp.from(Instant.now()))
                .update();
    }

    /** Dr platform:suspense:usdc / Cr the seller's wallet: balanced, cross-book, legal under V8 only. */
    private void crossBookAdjustment(UUID id, TestPayment payment) {
        jdbc.sql("""
                        INSERT INTO journal_entry (id, payment_id, payment_key, book, kind, description, effective_at)
                        VALUES (:id, :paymentId, :key, 'PLATFORM', 'ADJUSTMENT',
                                'Reconciliation: AMOUNT_MISMATCH moved to suspense', :at)
                        """)
                .param("id", id)
                .param("paymentId", PaymentProjection.paymentId(payment.key()))
                .param("key", payment.key())
                .param("at", Timestamp.from(Instant.now()))
                .update();
        posting(id, "platform:suspense:usdc", "DEBIT", TAMPER);
        posting(id, wallet(payment), "CREDIT", TAMPER);
    }

    private void insertMismatch(UUID runId, TestPayment payment, UUID adjustment) {
        jdbc.sql("""
                        INSERT INTO reconciliation_mismatch (id, run_id, payment_id, kind, ledger_amount_atomic,
                               chain_amount_atomic, asset, decimals, reported_tx_hash, chain_tx_hash,
                               adjustment_entry_id)
                        VALUES (:id, :runId, :paymentId, 'AMOUNT_MISMATCH', :ledger, :chain, 'USDC', 6, :tx, :tx,
                                :adjustment)
                        """)
                .param("id", UUID.randomUUID())
                .param("runId", runId)
                .param("paymentId", PaymentProjection.paymentId(payment.key()))
                .param("ledger", AMOUNT + TAMPER)
                .param("chain", AMOUNT)
                .param("tx", payment.txHash())
                .param("adjustment", adjustment)
                .update();
    }

    private void insertItem(UUID runId, TestPayment payment, UUID adjustment) {
        jdbc.sql("""
                        INSERT INTO reconciliation_item (run_id, payment_id, status, tx_hash, mismatch_kind,
                               ledger_amount_atomic, chain_amount_atomic, adjustment_entry_id)
                        VALUES (:runId, :paymentId, 'MISMATCH', :tx, 'AMOUNT_MISMATCH', :ledger, :chain, :adjustment)
                        """)
                .param("runId", runId)
                .param("paymentId", PaymentProjection.paymentId(payment.key()))
                .param("tx", payment.txHash())
                .param("ledger", AMOUNT + TAMPER)
                .param("chain", AMOUNT)
                .param("adjustment", adjustment)
                .update();
    }

    /** Raises the newest SALE as the demo does: a superuser session in replica mode, which skips the triggers. */
    private void tamperTheNewestSale() throws SQLException {
        try (Connection c = Superuser.dataSource(superuserDatabase).getConnection()) {
            c.setAutoCommit(false);
            try (Statement set = c.createStatement()) {
                set.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement update = c.prepareStatement(TAMPER_SQL)) {
                assertThat(update.executeUpdate()).isEqualTo(2);
            }
            c.commit();
        }
    }

    private void account(String code, String book, String type, String wallet) {
        jdbc.sql("""
                        INSERT INTO account (code, book, type, asset, decimals, wallet)
                        VALUES (:code, :book, :type, 'USDC', 6, :wallet) ON CONFLICT DO NOTHING
                        """)
                .param("code", code)
                .param("book", book)
                .param("type", type)
                .param("wallet", wallet)
                .update();
    }

    private void posting(UUID entry, String account, String side, long amount) {
        jdbc.sql("""
                        INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
                        VALUES (:entry, :account, :side, :amount, 'USDC', 6)
                        """)
                .param("entry", entry)
                .param("account", account)
                .param("side", side)
                .param("amount", amount)
                .update();
    }

    /** {@code debit - credit} of one account. */
    private long balanceOf(String code) {
        return jdbc.sql("""
                        SELECT coalesce(sum(CASE side WHEN 'DEBIT' THEN amount_atomic ELSE -amount_atomic END), 0)
                          FROM posting WHERE account_code = :code
                        """).param("code", code).query(Long.class).single();
    }

    private long wholeLedgerSum() {
        return jdbc.sql("""
                        SELECT coalesce(sum(CASE side WHEN 'DEBIT' THEN amount_atomic ELSE -amount_atomic END), 0)
                          FROM posting
                        """).query(Long.class).single();
    }

    /** Books whose postings do not sum to zero for some asset: the invariant ADR-0029 makes the database enforce. */
    private List<String> unbalancedBooks() {
        return jdbc.sql("""
                        SELECT a.book || '/' || p.asset
                          FROM posting p
                          JOIN account a ON a.code = p.account_code AND a.asset = p.asset
                         GROUP BY a.book, p.asset
                        HAVING sum(CASE p.side WHEN 'DEBIT' THEN p.amount_atomic ELSE -p.amount_atomic END) <> 0
                        """).query(String.class).list();
    }

    private List<UUID> adjustmentIds(TestPayment payment) {
        return jdbc.sql("SELECT id FROM journal_entry WHERE kind = 'ADJUSTMENT' AND payment_key = :key ORDER BY id")
                .param("key", payment.key())
                .query(UUID.class)
                .list();
    }

    private List<UUID> reversalsOf(UUID entry) {
        return jdbc.sql("SELECT id FROM journal_entry WHERE reverses_entry_id = :id")
                .param("id", entry)
                .query(UUID.class)
                .list();
    }

    private String bookOf(UUID entry) {
        return jdbc.sql("SELECT book FROM journal_entry WHERE id = :id")
                .param("id", entry)
                .query(String.class)
                .single();
    }

    private UUID paymentIdOf(UUID entry) {
        return jdbc.sql("SELECT payment_id FROM journal_entry WHERE id = :id")
                .param("id", entry)
                .query(UUID.class)
                .single();
    }

    private List<String> legsOf(UUID entry) {
        return jdbc.sql("""
                        SELECT side || ' ' || account_code || ' ' || amount_atomic FROM posting
                         WHERE entry_id = :id ORDER BY id
                        """).param("id", entry).query(String.class).list();
    }

    /** Flyway's history is readable by the owner only (V7 revokes it from the runtime role). */
    private String headVersion() {
        return Superuser.owner(superuserDatabase)
                .sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                .query(String.class)
                .single();
    }

    private static String wallet(TestPayment payment) {
        return "seller:" + payment.payTo().toLowerCase(Locale.ROOT) + ":wallet";
    }

    private static String suspense(TestPayment payment) {
        return "seller:" + payment.payTo().toLowerCase(Locale.ROOT) + ":suspense:usdc";
    }
}
