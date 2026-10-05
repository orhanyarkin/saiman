package io.github.orhanyarkin.saiman.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.Superuser;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.Stories;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
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
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database half of the ledger's invariants (V1__ledger.sql): the deferred balanced-postings trigger fires at
 * COMMIT (so these tests commit for real through a {@link TransactionTemplate}; a rolled-back test transaction
 * would never reach it; the commit failure surfaces as a translated {@link DataIntegrityViolationException}), entries and postings are append-only, and one-per-payment kinds are unique.
 */
@LedgerIntegrationTest
class LedgerSchemaTests {

    private static final int DB_TRIES = Pbt.dbTries();

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private SuperuserDatabase superuserDatabase;

    @Test
    void balancedEntryCommits() {
        UUID entry = UUID.randomUUID();
        String wallet = wallet();

        tx.executeWithoutResult(s -> insertRaw(entry, wallet, List.of(+500L, -500L)));

        assertThat(jdbc.sql("SELECT count(*) FROM posting WHERE entry_id = :id")
                        .param("id", entry)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    /** P3 (database half): one posting perturbed by a delta in a balanced entry is rejected at commit. */
    @Test
    void p3PerturbedEntryIsRejectedAtCommit() {
        Pbt.forAll(DB_TRIES, this::p3PerturbedEntryIsRejectedAtCommitTry);
    }

    private void p3PerturbedEntryIsRejectedAtCommitTry(int tryIndex, long seed) {
        var random = new SplittableRandom(seed);
        long amount = random.nextLong(2, 1_000_000_000_001L);
        String wallet = wallet();
        Account available = ChartOfAccounts.buyerAvailable(wallet);
        Account encumbered = ChartOfAccounts.buyerEncumbered(wallet);
        List<Posting> perturbed = Stories.perturb(
                List.of(Posting.debit(encumbered, Money.usdc(amount)), Posting.credit(available, Money.usdc(amount))),
                random);
        List<Long> signed = perturbed.stream().map(Posting::signedAtomic).toList();
        UUID entry = UUID.randomUUID();

        Pbt.check(tryIndex, seed, () -> "postings " + signed, () -> {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertRaw(entry, wallet, signed)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("JDBC commit")
                    .hasMessageContaining("unbalanced");
            assertThat(count("SELECT count(*) FROM journal_entry WHERE id = :id", entry))
                    .isZero();
        });
    }

    @Test
    void entryWithOnePostingIsRejectedAtCommit() {
        UUID entry = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertRaw(entry, wallet(), List.of(+500L))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("JDBC commit")
                .hasMessageContaining("at least two are required");
    }

    @Test
    void entryWithoutPostingsIsRejectedAtCommit() {
        UUID entry = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertRaw(entry, wallet(), List.of())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("JDBC commit")
                .hasMessageContaining("at least two are required");
    }

    /** The triggers still guard the books against a role that has the privileges (here the superuser). */
    @Test
    void entriesAndPostingsAreAppendOnly() {
        UUID entry = UUID.randomUUID();
        tx.executeWithoutResult(s -> insertRaw(entry, wallet(), List.of(+700L, -700L)));
        JdbcClient jdbc = Superuser.jdbc(superuserDatabase);

        assertThatThrownBy(() -> jdbc.sql("UPDATE posting SET amount_atomic = amount_atomic + 1 WHERE entry_id = :id")
                        .param("id", entry)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM posting WHERE entry_id = :id")
                        .param("id", entry)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entry SET description = 'edited' WHERE id = :id")
                        .param("id", entry)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entry WHERE id = :id")
                        .param("id", entry)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE posting").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE journal_entry CASCADE").update())
                .hasMessageContaining("append-only");
    }

    /**
     * ADR-0024: the runtime role ({@code ledger_app}) is refused by privilege before any trigger runs, and it can
     * neither switch the triggers off for its session nor disable them on the table.
     */
    @Test
    void theRuntimeRoleCannotRewriteTheBooksOrBypassTheTriggers() {
        UUID entry = UUID.randomUUID();
        tx.executeWithoutResult(s -> insertRaw(entry, wallet(), List.of(+800L, -800L)));

        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ledger_app");
        for (String sql : List.of(
                "UPDATE posting SET amount_atomic = amount_atomic + 1 WHERE entry_id = :id",
                "DELETE FROM posting WHERE entry_id = :id",
                "UPDATE journal_entry SET description = 'edited' WHERE id = :id",
                "DELETE FROM journal_entry WHERE id = :id")) {
            assertThatThrownBy(() -> jdbc.sql(sql).param("id", entry).update())
                    .as(sql)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
        for (String sql : List.of(
                "TRUNCATE posting",
                "SET session_replication_role = replica",
                "ALTER TABLE posting DISABLE TRIGGER posting_immutable",
                "ALTER TABLE journal_entry DISABLE TRIGGER ALL",
                "SELECT count(*) FROM flyway_schema_history")) {
            assertThatThrownBy(() -> jdbc.sql(sql).update())
                    .as(sql)
                    .isInstanceOf(DataAccessException.class)
                    .satisfies(e -> assertThat(
                                    NestedExceptionUtils.getMostSpecificCause(e).getMessage())
                            .containsAnyOf("permission denied", "must be owner"));
        }
        assertThat(jdbc.sql("SELECT sum(amount_atomic) FROM posting WHERE entry_id = :id")
                        .param("id", entry)
                        .query(Long.class)
                        .single())
                .isEqualTo(1600L);
    }

    /** V7: insert-only records refuse UPDATE, DELETE and TRUNCATE from the runtime role. */
    @Test
    void insertOnlyRecordsRefuseRewritesFromTheRuntimeRole() {
        Map<String, String> anyColumn = Map.of(
                "reconciliation_mismatch", "kind",
                "reconciliation_item", "status",
                "credit_note_corroboration", "tx_hash",
                "account", "code",
                "inbox", "topic");
        anyColumn.forEach((table, column) -> {
            for (String sql : List.of(
                    "UPDATE " + table + " SET " + column + " = " + column + " WHERE false",
                    "DELETE FROM " + table + " WHERE false",
                    "TRUNCATE " + table)) {
                assertRefused(sql);
            }
        });
    }

    /** ADR-0024: the runtime role has DML only; no DDL anywhere, no sequence resets, no trigger or function changes. */
    @Test
    void theRuntimeRoleCannotChangeTheSchema() {
        for (String sql : List.of(
                "CREATE TABLE ledger.app_made (id int)",
                "CREATE TABLE public.app_made (id int)",
                "CREATE FUNCTION ledger.app_made() RETURNS int LANGUAGE sql AS 'SELECT 1'",
                "DROP TRIGGER posting_immutable ON posting",
                "ALTER FUNCTION reject_ledger_change() RENAME TO app_renamed",
                "DROP FUNCTION reject_ledger_change() CASCADE",
                "ALTER TABLE posting OWNER TO ledger_app")) {
            assertRefused(sql);
        }
    }

    /** Default privileges: what ledger_owner creates after V7 is usable by ledger_app; Flyway's history is not. */
    @Test
    void laterOwnerObjectsGetTheDefaultGrantsButNotSequenceResets() {
        JdbcClient owner = Superuser.owner(superuserDatabase);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String table = "later_" + suffix;
        String sequence = "later_seq_" + suffix;
        owner.sql("CREATE TABLE " + table + " (id int)").update();
        owner.sql("CREATE SEQUENCE " + sequence).update();
        try {
            jdbc.sql("INSERT INTO " + table + " VALUES (1)").update();
            assertThat(jdbc.sql("SELECT count(*) FROM " + table)
                            .query(Long.class)
                            .single())
                    .isOne();
            assertThat(jdbc.sql("SELECT nextval('" + sequence + "')")
                            .query(Long.class)
                            .single())
                    .isOne();
            assertRefused("SELECT setval('" + sequence + "', 100)");
            assertRefused("SELECT count(*) FROM flyway_schema_history");
        } finally {
            owner.sql("DROP TABLE " + table).update();
            owner.sql("DROP SEQUENCE " + sequence).update();
        }
    }

    private void assertRefused(String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).query().listOfRows())
                .as(sql)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(
                                NestedExceptionUtils.getMostSpecificCause(e).getMessage())
                        .containsAnyOf("permission denied", "must be owner"));
    }

    @Test
    void oncePerPaymentKindsAreUniquePerBook() {
        TestPayment payment = TestPayment.random(new SplittableRandom(7), 20_000);
        ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED);

        // A second ENCUMBER for the same payment key and book, bypassing the state machine.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("""
                                INSERT INTO journal_entry (id, payment_key, book, kind, description, effective_at)
                                VALUES (:id, :key, 'BUYER', 'ENCUMBER', 'duplicate', now())
                                """)
                        .param("id", UUID.randomUUID())
                        .param("key", payment.key())
                        .update()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("journal_entry_once_per_payment");
    }

    /**
     * ADR-0017's known gap, which scripts/ledger-tamper-demo.sh relies on: a superuser session in replica mode
     * skips the immutability triggers. Since M6 (ADR-0024) only a superuser or anyone holding the ledger_owner credential can; the runtime role
     * can't (above).
     */
    @Test
    void replicaModeBypassesTheTriggersAsTheTamperDemoExpects() throws SQLException {
        UUID entry = UUID.randomUUID();
        tx.executeWithoutResult(s -> insertRaw(entry, wallet(), List.of(+900L, -900L)));

        try (Connection c = Superuser.dataSource(superuserDatabase).getConnection()) {
            c.setAutoCommit(false);
            try (Statement set = c.createStatement()) {
                set.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement update =
                    c.prepareStatement("UPDATE posting SET amount_atomic = amount_atomic + 5000 WHERE entry_id = ?")) {
                update.setObject(1, entry);
                update.executeUpdate();
            }
            c.commit();
        }

        assertThat(jdbc.sql("SELECT sum(amount_atomic) FROM posting WHERE entry_id = :id")
                        .param("id", entry)
                        .query(Long.class)
                        .single())
                .isEqualTo(900L + 900L + 10_000L);
    }

    /** Inserts accounts, an ADJUSTMENT entry and postings with raw SQL (positive = debit, negative = credit). */
    private void insertRaw(UUID entry, String wallet, List<Long> signedAmounts) {
        Account debit = ChartOfAccounts.buyerEncumbered(wallet);
        Account credit = ChartOfAccounts.buyerAvailable(wallet);
        for (Account account : List.of(debit, credit)) {
            jdbc.sql("""
                            INSERT INTO account (code, book, type, asset, decimals, wallet)
                            VALUES (:code, 'BUYER', 'ASSET', 'USDC', 6, :wallet) ON CONFLICT DO NOTHING
                            """).param("code", account.code()).param("wallet", wallet).update();
        }
        jdbc.sql("""
                        INSERT INTO journal_entry (id, book, kind, description, effective_at)
                        VALUES (:id, 'BUYER', 'ADJUSTMENT', 'schema test', :at)
                        """)
                .param("id", entry)
                .param("at", Timestamp.from(Instant.now()))
                .update();
        for (long signed : signedAmounts) {
            jdbc.sql("""
                            INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
                            VALUES (:entry, :account, :side, :amount, 'USDC', 6)
                            """)
                    .param("entry", entry)
                    .param("account", signed > 0 ? debit.code() : credit.code())
                    .param("side", signed > 0 ? "DEBIT" : "CREDIT")
                    .param("amount", Math.abs(signed))
                    .update();
        }
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private static String wallet() {
        return TestPayment.address(new SplittableRandom()).toLowerCase(java.util.Locale.ROOT);
    }
}
