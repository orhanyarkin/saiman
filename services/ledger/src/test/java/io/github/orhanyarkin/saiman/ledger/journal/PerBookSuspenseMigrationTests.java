package io.github.orhanyarkin.saiman.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.evmrpc.UsdcTransfer;
import io.github.orhanyarkin.saiman.ledger.FakeChain;
import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.Superuser;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentBook;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationReport;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationReports;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationService;
import io.github.orhanyarkin.saiman.shared.money.Money;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0029 on a tampered database: the context's Flyway stops at V8 ({@code spring.flyway.target}), the test seeds
 * exactly what {@code make ledger-tamper-demo} plus a reconciliation run produced under the old rule (a SALE raised
 * by 5000 and a cross-book ADJUSTMENT against {@code platform:suspense:usdc}), and then migrates to head.
 *
 * <p>Verification 1, 2, 5 and 6 of the ADR. Verification 3 (an unlinked adjustment) is in
 * {@link UnlinkedAdjustmentMigrationTests}, verification 4 (a clean database) in {@code MigrateOneShotTests}.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = {"saiman.test.context=per-book-suspense", "spring.flyway.target=8"})
class PerBookSuspenseMigrationTests {

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
    private ReconciliationService reconciliation;

    @Autowired
    private ReconciliationReports reports;

    @Autowired
    private FakeChain chain;

    @Autowired
    private SuperuserDatabase superuserDatabase;

    private static boolean migrated;
    private static TestPayment payment;
    private static UUID reconciliationRunId;
    private static UUID originalAdjustment;
    private static long sellerWalletBefore;
    private static List<String> originalLegsBefore;

    /** Seeds the pre-ADR-0029 shape once, under V8's rule, and migrates to head. */
    @BeforeEach
    void seedAtV8AndMigrateOnce() throws SQLException {
        if (migrated) {
            return;
        }
        assertThat(headVersion())
                .as("the context stops at V8 so the old rule still applies")
                .isEqualTo("8");

        payment = TestPayment.random(new SplittableRandom(29), AMOUNT);
        ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED);
        tamperTheSale();

        reconciliationRunId = UUID.randomUUID();
        originalAdjustment =
                PaymentBook.entryId(payment.key(), LedgerBook.PLATFORM, "ADJUSTMENT:" + reconciliationRunId);
        seedCrossBookAdjustment();

        sellerWalletBefore = balanceOf(sellerWallet());
        originalLegsBefore = legsOf(originalAdjustment);
        assertThat(sellerWalletBefore)
                .as("the tamper is already parked in the platform pot")
                .isEqualTo(AMOUNT);
        assertThat(balanceOf("platform:suspense:usdc")).isEqualTo(TAMPER);

        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target(MigrationVersion.LATEST)
                .load()
                .migrate();
        assertThat(headVersion()).isEqualTo("10");
        migrated = true;
    }

    /** Verification 1 and 5. */
    @Test
    void migrationMovesTheDifferenceIntoTheSellerBookWithoutEditingHistory() {
        assertThat(unbalancedBooks()).as("every book balances per asset").isEmpty();
        assertThat(wholeLedgerSum()).as("the whole-ledger sum stays zero").isZero();
        assertThat(balanceOf(sellerWallet()))
                .as("the seller's wallet is untouched by the correction")
                .isEqualTo(sellerWalletBefore);
        assertThat(balanceOf("platform:suspense:usdc"))
                .as("the retired platform pot nets to zero")
                .isZero();
        assertThat(balanceOf(sellerSuspense()))
                .as("the difference now sits in the seller's own suspense account, as a debit")
                .isEqualTo(TAMPER);

        // The original entry is untouched and reversed exactly once.
        assertThat(legsOf(originalAdjustment)).isEqualTo(originalLegsBefore);
        assertThat(jdbc.sql("SELECT book FROM journal_entry WHERE id = :id")
                        .param("id", originalAdjustment)
                        .query(String.class)
                        .single())
                .isEqualTo("PLATFORM");
        assertThat(jdbc.sql("SELECT id FROM journal_entry WHERE reverses_entry_id = :id")
                        .param("id", originalAdjustment)
                        .query(UUID.class)
                        .list())
                .hasSize(1);
        assertThat(legsOf(jdbc.sql("SELECT id FROM journal_entry WHERE reverses_entry_id = :id")
                        .param("id", originalAdjustment)
                        .query(UUID.class)
                        .single()))
                .as("the reversal is the opposite of the original, still cross-book")
                .containsExactlyInAnyOrder("CREDIT platform:suspense:usdc 5000", "DEBIT " + sellerWallet() + " 5000");

        // The replacement carries the id the new code derives, which is what keeps a later run idempotent.
        UUID expected = PaymentBook.entryId(payment.key(), LedgerBook.SELLER, "ADJUSTMENT:" + reconciliationRunId);
        assertThat(adjustmentIds()).containsExactlyInAnyOrder(originalAdjustment, expected);
        assertThat(legsOf(expected))
                .containsExactlyInAnyOrder("DEBIT " + sellerSuspense() + " 5000", "CREDIT " + sellerWallet() + " 5000");
        assertThat(jdbc.sql("SELECT payment_id FROM journal_entry WHERE id = :id")
                        .param("id", expected)
                        .query(UUID.class)
                        .single())
                .as("the replacement is about the payment, so reconciliation counts it in the nets")
                .isEqualTo(PaymentProjection.paymentId(payment.key()));
    }

    /** Verification 2: V10's trigger refuses a cross-book entry at commit; a single-book one still commits. */
    @Test
    void theNewRuleRefusesCrossBookEntries() {
        String buyer = address();
        String seller = address();
        account("buyer:" + buyer + ":wallet:available", "BUYER", "ASSET", buyer);
        account("seller:" + seller + ":wallet", "SELLER", "ASSET", seller);
        UUID crossBook = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    entry(crossBook, "BUYER");
                    posting(crossBook, "buyer:" + buyer + ":wallet:available", "DEBIT", 700);
                    posting(crossBook, "seller:" + seller + ":wallet", "CREDIT", 700);
                }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("JDBC commit")
                .hasMessageContaining("crosses books");
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entry WHERE id = :id")
                        .param("id", crossBook)
                        .query(Long.class)
                        .single())
                .isZero();

        UUID singleBook = UUID.randomUUID();
        account("buyer:" + buyer + ":expense:data", "BUYER", "EXPENSE", buyer);
        tx.executeWithoutResult(status -> {
            entry(singleBook, "BUYER");
            posting(singleBook, "buyer:" + buyer + ":expense:data", "DEBIT", 700);
            posting(singleBook, "buyer:" + buyer + ":wallet:available", "CREDIT", 700);
        });
        assertThat(jdbc.sql("SELECT count(*) FROM posting WHERE entry_id = :id")
                        .param("id", singleBook)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(wholeLedgerSum()).isZero();
    }

    /**
     * Verification 6: a reconciliation pass over the migrated database writes nothing. The run recomputes the
     * payment's nets, which the reversal and the replacement leave at the chain's movement, and the replacement
     * already carries the id {@link PaymentBook#entryId} derives for {@code (payment, SELLER, run)} — verification 1
     * asserts that id directly, because a drifted id would only show up as a duplicate if the nets still differed.
     */
    @Test
    void reconciliationAfterTheMigrationPostsNothingAndLeavesTheFindingAlone() {
        chain.mine(matchingReceipt());
        List<UUID> adjustmentsBefore = adjustmentIds();

        UUID runId = reconciliation.runNow().orElseThrow();
        ReconciliationReport report = reports.report(runId).orElseThrow();

        UUID paymentId = PaymentProjection.paymentId(payment.key());
        assertThat(report.items())
                .filteredOn(item -> item.paymentId().equals(paymentId))
                .singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo("MATCHED"));
        assertThat(adjustmentIds()).isEqualTo(adjustmentsBefore);
        assertThat(jdbc.sql("SELECT kind FROM reconciliation_mismatch WHERE payment_id = :id ORDER BY kind")
                        .param("id", paymentId)
                        .query(String.class)
                        .list())
                .as("no new finding: the books already agree with the chain")
                .containsExactly("AMOUNT_MISMATCH");
        assertThat(balanceOf(sellerSuspense())).isEqualTo(TAMPER);
        assertThat(report.suspenseByBook())
                .filteredOn(book -> "SELLER".equals(book.book()))
                .singleElement()
                .satisfies(book -> {
                    assertThat(book.balance()).isEqualTo(Money.usdc(TAMPER));
                    assertThat(book.side()).isEqualTo("DEBIT");
                });
        assertThat(unbalancedBooks()).isEmpty();
        assertThat(wholeLedgerSum()).isZero();
    }

    private UsdcReceipt matchingReceipt() {
        String payer = payment.authorization().payer();
        return new UsdcReceipt(
                payment.txHash(),
                FakeChain.SAFE.number() - 10,
                true,
                List.of(new UsdcTransfer(payer, payment.payTo(), AMOUNT)),
                List.of(payer + ":" + payment.authorization().nonce()));
    }

    /** Raises the SALE as the demo does: a superuser session in replica mode, which skips the triggers. */
    private void tamperTheSale() throws SQLException {
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

    /** The run and finding of the old reconciliation pass, plus its cross-book ADJUSTMENT (legal under V8). */
    private void seedCrossBookAdjustment() {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO reconciliation_run (id, started_at, status, network)
                            VALUES (:id, :at, 'COMPLETED', 'eip155:84532')
                            """)
                    .param("id", reconciliationRunId)
                    .param("at", Timestamp.from(Instant.now()))
                    .update();
            account("platform:suspense:usdc", "PLATFORM", "SUSPENSE", null);
            jdbc.sql("""
                            INSERT INTO journal_entry (id, payment_id, payment_key, book, kind, description,
                                                       effective_at)
                            VALUES (:id, :paymentId, :key, 'PLATFORM', 'ADJUSTMENT',
                                    'Reconciliation: AMOUNT_MISMATCH moved to suspense', :at)
                            """)
                    .param("id", originalAdjustment)
                    .param("paymentId", PaymentProjection.paymentId(payment.key()))
                    .param("key", payment.key())
                    .param("at", Timestamp.from(Instant.now()))
                    .update();
            posting(originalAdjustment, "platform:suspense:usdc", "DEBIT", TAMPER);
            posting(originalAdjustment, sellerWallet(), "CREDIT", TAMPER);
            jdbc.sql("""
                            INSERT INTO reconciliation_mismatch (id, run_id, payment_id, kind, ledger_amount_atomic,
                                   chain_amount_atomic, asset, decimals, reported_tx_hash, chain_tx_hash,
                                   adjustment_entry_id)
                            VALUES (:id, :runId, :paymentId, 'AMOUNT_MISMATCH', :ledger, :chain, 'USDC', 6, :tx, :tx,
                                    :adjustment)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("runId", reconciliationRunId)
                    .param("paymentId", PaymentProjection.paymentId(payment.key()))
                    .param("ledger", AMOUNT + TAMPER)
                    .param("chain", AMOUNT)
                    .param("tx", payment.txHash())
                    .param("adjustment", originalAdjustment)
                    .update();
        });
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

    private void entry(UUID id, String book) {
        jdbc.sql("""
                        INSERT INTO journal_entry (id, book, kind, description, effective_at)
                        VALUES (:id, :book, 'ADJUSTMENT', 'per-book rule test', now())
                        """).param("id", id).param("book", book).update();
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

    private List<UUID> adjustmentIds() {
        return jdbc.sql("SELECT id FROM journal_entry WHERE kind = 'ADJUSTMENT' AND payment_key = :key ORDER BY id")
                .param("key", payment.key())
                .query(UUID.class)
                .list();
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

    private static String sellerWallet() {
        return "seller:" + payment.payTo().toLowerCase(Locale.ROOT) + ":wallet";
    }

    private static String sellerSuspense() {
        return "seller:" + payment.payTo().toLowerCase(Locale.ROOT) + ":suspense:usdc";
    }

    private static String address() {
        return TestPayment.address(new SplittableRandom()).toLowerCase(Locale.ROOT);
    }
}
