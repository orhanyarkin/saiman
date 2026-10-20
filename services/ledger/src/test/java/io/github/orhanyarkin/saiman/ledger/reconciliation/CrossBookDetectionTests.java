package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.Superuser;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-0029's per-book invariant is enforced only for rows written after V10, and only while the trigger runs: a
 * writer holding the owner credential, or a superuser setting {@code session_replication_role = replica} (the
 * documented tamper-demo gap, {@code scripts/ledger-tamper-demo.sh}), can re-point a posting at another book's
 * account. The entry still balances per asset, so {@link ReconciliationRepository#unbalancedEntries} stays zero.
 * {@link ReconciliationRepository#crossBookEntries} is the detective control; a context of its own, because the
 * tampering here is global and not undone.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = "saiman.test.context=cross-book-detection")
class CrossBookDetectionTests {

    @Autowired
    private ReconciliationRepository repository;

    @Autowired
    private ReconciliationService service;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SuperuserDatabase superuserDatabase;

    private final SplittableRandom random = new SplittableRandom();

    @Test
    void aPostingRepointedAtAnotherBookIsCountedAndGauged() throws SQLException {
        TestPayment payment = TestPayment.random(random, 20_000);
        ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);
        ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED);
        assertThat(repository.crossBookEntries())
                .as("honest entries are single-book")
                .isZero();

        repointSaleRevenueAtTheBuyersBook(payment.key());

        assertThat(repository.unbalancedEntries())
                .as("the tampered entry still balances per asset, which is why this check exists")
                .isZero();
        assertThat(repository.crossBookEntries()).isEqualTo(1);

        assertThat(service.runNow()).isPresent();

        assertThat(meters.get("saiman.ledger.reconciliation.cross_book.entries")
                        .gauge()
                        .value())
                .isEqualTo(1);
    }

    /** Moves the SALE's credit leg onto a BUYER-book account of the same asset, as a trigger bypass could. */
    private void repointSaleRevenueAtTheBuyersBook(String paymentKey) throws SQLException {
        UUID sale = jdbc.sql("SELECT id FROM journal_entry WHERE payment_key = :key AND kind = 'SALE'")
                .param("key", paymentKey)
                .query(UUID.class)
                .single();
        // The same asset and decimals as the posting, or it would drop out of the check's join to account.
        String buyerAccount = jdbc.sql(
                        "SELECT code FROM account WHERE book = 'BUYER' AND asset = 'USDC' AND decimals = 6 ORDER BY code LIMIT 1")
                .query(String.class)
                .single();
        try (Connection c = Superuser.dataSource(superuserDatabase).getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");
                s.execute("UPDATE posting SET account_code = '%s' WHERE entry_id = '%s' AND side = 'CREDIT'"
                        .formatted(buyerAccount, sale));
            }
            c.commit();
        }
    }
}
