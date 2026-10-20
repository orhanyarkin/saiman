package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.Superuser;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.testsupport.PostgresContainerConfiguration.SuperuserDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * A suspense balance is only as bounded as the postings a writer can forge (Kafka is unauthenticated until M6 and
 * {@code posting.amount_atomic} is capped at 2^53-1 per row, not per account). Each book's figure is clamped to
 * {@code ±Long.MAX_VALUE} by the repository, so the headline figure — the sum over books — must be clamped too:
 * summing two saturated books with {@code Math::addExact} throws, which would be a 500 on the reconciliation report
 * and would silently revoke what {@code suspenseBalances} promises.
 *
 * <p>Its own {@code saiman.test.context}: the balances here are global, so they would break any sibling test
 * asserting an absolute suspense figure.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = "saiman.test.context=suspense-saturation")
class SuspenseSaturationTests {

    /** The per-posting cap (V3 {@code posting_amount_bounded}); 1025 of them pass {@code Long.MAX_VALUE}. */
    private static final long MAX_POSTING = 9_007_199_254_740_991L;

    private static final int POSTINGS = 1_025;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RestTestClient client;

    @Autowired
    private SuperuserDatabase superuserDatabase;

    private final SplittableRandom random = new SplittableRandom();

    @Test
    void aSuspenseBalanceBeyondLongMaxStillRendersTheReport() throws SQLException {
        String payer = TestPayment.address(random).toLowerCase(Locale.ROOT);
        String payTo = TestPayment.address(random).toLowerCase(Locale.ROOT);
        floodSuspense("buyer:" + payer + ":suspense:usdc", "buyer:" + payer + ":wallet:available", "BUYER", payer);
        floodSuspense("seller:" + payTo + ":suspense:usdc", "seller:" + payTo + ":wallet", "SELLER", payTo);
        UUID runId = insertRun();

        ReconciliationReport report = client.get()
                .uri("/api/v1/reconciliation/runs/" + runId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ReconciliationReport.class)
                .returnResult()
                .getResponseBody();

        assertThat(report).isNotNull();
        assertThat(report.suspense())
                .as("the sum over books saturates instead of overflowing")
                .isEqualTo(Money.usdc(Long.MAX_VALUE));
        assertThat(report.suspenseSide()).isEqualTo("DEBIT");
        assertThat(report.suspenseByBook())
                .filteredOn(book -> "BUYER".equals(book.book()) || "SELLER".equals(book.book()))
                .hasSize(2)
                .allSatisfy(book -> {
                    assertThat(book.balance()).isEqualTo(Money.usdc(Long.MAX_VALUE));
                    assertThat(book.side()).isEqualTo("DEBIT");
                });
    }

    /**
     * Debits one book's suspense account past {@code Long.MAX_VALUE} against that book's wallet, as a writer with a
     * trigger bypass could (the same {@code session_replication_role = replica} that
     * {@code scripts/ledger-tamper-demo.sh} uses): the deferred balance trigger would otherwise walk every one of
     * these postings per row.
     */
    private void floodSuspense(String suspense, String wallet, String book, String address) throws SQLException {
        UUID entry = UUID.randomUUID();
        try (Connection c = Superuser.dataSource(superuserDatabase).getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");
                s.execute("""
                        INSERT INTO account (code, book, type, asset, decimals, wallet) VALUES
                            ('%s', '%s', 'SUSPENSE', 'USDC', 6, '%s'),
                            ('%s', '%s', 'ASSET', 'USDC', 6, '%s')
                        ON CONFLICT DO NOTHING
                        """.formatted(suspense, book, address, wallet, book, address));
                s.execute("""
                        INSERT INTO journal_entry (id, book, kind, description, effective_at)
                        VALUES ('%s', '%s', 'ADJUSTMENT', 'forged suspense flood', now())
                        """.formatted(entry, book));
                s.execute("""
                        INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
                        SELECT '%1$s', code, side, %4$d, 'USDC', 6
                          FROM (VALUES ('%2$s', 'DEBIT'), ('%3$s', 'CREDIT')) AS legs(code, side),
                               generate_series(1, %5$d)
                        """.formatted(entry, suspense, wallet, MAX_POSTING, POSTINGS));
            }
            c.commit();
        }
    }

    private UUID insertRun() {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO reconciliation_run (id, started_at, finished_at, status, network)
                        VALUES (:id, :at, :at, 'COMPLETED', 'eip155:84532')
                        """)
                .param("id", runId)
                .param("at", Timestamp.from(Instant.now()))
                .update();
        return runId;
    }
}
