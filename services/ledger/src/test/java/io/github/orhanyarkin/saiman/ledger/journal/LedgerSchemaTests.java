package io.github.orhanyarkin.saiman.ledger.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.Stories;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final int DB_TRIES = 50;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PaymentLedgerService ledger;

    static Stream<Arguments> dbTries() {
        return Pbt.tries(DB_TRIES);
    }

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
    @ParameterizedTest(name = "P3 db try {0} seed {1}")
    @MethodSource("dbTries")
    void p3PerturbedEntryIsRejectedAtCommit(int tryIndex, long seed) {
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

    @Test
    void entriesAndPostingsAreAppendOnly() {
        UUID entry = UUID.randomUUID();
        tx.executeWithoutResult(s -> insertRaw(entry, wallet(), List.of(+700L, -700L)));

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
     * skips the immutability triggers. (Per-service roles without that privilege are M6.)
     */
    @Test
    void replicaModeBypassesTheTriggersAsTheTamperDemoExpects() {
        UUID entry = UUID.randomUUID();
        tx.executeWithoutResult(s -> insertRaw(entry, wallet(), List.of(+900L, -900L)));

        tx.executeWithoutResult(s -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE posting SET amount_atomic = amount_atomic + 5000 WHERE entry_id = :id")
                    .param("id", entry)
                    .update();
        });

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
