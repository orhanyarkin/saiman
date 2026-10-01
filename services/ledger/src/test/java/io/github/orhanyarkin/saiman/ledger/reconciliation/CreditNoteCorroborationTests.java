package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.evmrpc.UsdcTransfer;
import io.github.orhanyarkin.saiman.ledger.FakeChain;
import io.github.orhanyarkin.saiman.ledger.FakeSellerCreditNotes;
import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * M4b audit (ADR-0021): a credit note touches no wallet, so the chain cannot expose a forged {@code CreditNoteIssued}.
 * Reconciliation asks seller-api ({@link FakeSellerCreditNotes} here) and reports {@code CREDIT_NOTE_UNCORROBORATED}
 * instead of MATCHED; a seller that cannot answer leaves the item PENDING. Same context as {@link ReconciliationTests}.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = "saiman.test.context=reconciliation")
class CreditNoteCorroborationTests {

    private static final long AMOUNT = 20_000;

    @Autowired
    private ReconciliationService service;

    @Autowired
    private ReconciliationReports reports;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private FakeChain chain;

    @Autowired
    private FakeSellerCreditNotes sellers;

    @Autowired
    private JdbcClient jdbc;

    private final SplittableRandom random = new SplittableRandom();

    @Test
    void aForgedCreditNoteOnARealSaleIsUncorroboratedAndPostsNothing() {
        TestPayment payment = credited();
        int entries = entryCount(payment);

        ReconciliationReport.Item item = item(runNow(), payment);

        assertThat(item.status()).isEqualTo("MISMATCH");
        assertThat(item.sellerState()).isEqualTo("CREDITED");
        assertThat(item.chainState()).isEqualTo("USED");
        assertThat(item.mismatch()).isNotNull();
        assertThat(item.mismatch().kind()).isEqualTo("CREDIT_NOTE_UNCORROBORATED");
        assertThat(item.mismatch().ledgerValue()).isEqualTo(Money.usdc(AMOUNT));
        assertThat(item.mismatch().chainValue()).isNull();
        assertThat(item.mismatch().adjustmentEntryId()).isNull();
        assertThat(mismatchKinds(payment)).containsExactly("CREDIT_NOTE_UNCORROBORATED");
        assertThat(entryCount(payment)).as("nothing is posted").isEqualTo(entries);
        assertThat(liability(payment))
                .as("the liability stays for a human REVERSAL")
                .isEqualTo(AMOUNT);
        assertThat(corroborations(payment))
                .as("a negative answer is never cached")
                .isZero();
        assertThat(sellers.calls(payment.key())).isOne();
    }

    @Test
    void aGenuineCreditNoteIsMatchedAndTheSellerIsAskedOnlyOnce() {
        TestPayment payment = credited();
        sellers.issue(payment.key(), payment.txHash(), AMOUNT);

        assertThat(item(runNow(), payment).status()).isEqualTo("MATCHED");
        assertThat(item(runNow(), payment).status()).isEqualTo("MATCHED");

        assertThat(sellers.calls(payment.key())).isEqualTo(1);
        assertThat(corroborations(payment)).isOne();
        assertThat(mismatchKinds(payment)).isEmpty();
    }

    @Test
    void anUnreachableSellerLeavesThePaymentPendingWithoutAFinding() {
        TestPayment payment = credited();
        sellers.down(payment.key());
        try {
            ReconciliationReport report = runNow();

            assertThat(report.status()).isEqualTo("PARTIAL");
            ReconciliationReport.Item item = item(report, payment);
            assertThat(item.status()).isEqualTo("PENDING");
            assertThat(item.mismatch()).isNull();
            assertThat(mismatchKinds(payment)).isEmpty();
            assertThat(corroborations(payment)).isZero();
        } finally {
            sellers.up(payment.key());
        }

        sellers.issue(payment.key(), payment.txHash(), AMOUNT);
        assertThat(item(runNow(), payment).status()).isEqualTo("MATCHED");
    }

    @Test
    void aSellerRowWithAnotherAmountOrTxHashIsUncorroborated() {
        TestPayment otherAmount = credited();
        sellers.issue(otherAmount.key(), otherAmount.txHash(), AMOUNT - 1);
        TestPayment otherTx = credited();
        String sellerTx = "0x" + "ab".repeat(32);
        sellers.issue(otherTx.key(), sellerTx, AMOUNT);

        ReconciliationReport report = runNow();

        ReconciliationReport.Item amountItem = item(report, otherAmount);
        assertThat(amountItem.status()).isEqualTo("MISMATCH");
        assertThat(amountItem.mismatch().kind()).isEqualTo("CREDIT_NOTE_UNCORROBORATED");
        assertThat(amountItem.mismatch().ledgerValue()).isEqualTo(Money.usdc(AMOUNT));
        assertThat(amountItem.mismatch().chainValue()).isEqualTo(Money.usdc(AMOUNT - 1));
        assertThat(item(report, otherTx).status()).isEqualTo("MISMATCH");
        Map<String, Object> row = jdbc.sql("""
                        SELECT reported_tx_hash, chain_tx_hash, ledger_amount_atomic, chain_amount_atomic
                          FROM reconciliation_mismatch WHERE payment_id = :id AND kind = 'CREDIT_NOTE_UNCORROBORATED'
                        """)
                .param("id", PaymentProjection.paymentId(otherTx.key()))
                .query()
                .singleRow();
        assertThat(row.get("reported_tx_hash")).isEqualTo(otherTx.txHash());
        assertThat(row.get("chain_tx_hash")).isEqualTo(sellerTx);
        assertThat(row.get("chain_amount_atomic")).isEqualTo(AMOUNT);
        assertThat(corroborations(otherAmount) + corroborations(otherTx)).isZero();
    }

    @Test
    void aSettledPaymentWithoutACreditNoteNeverAsksTheSeller() {
        TestPayment payment = TestPayment.random(random, AMOUNT);
        ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);
        ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED);
        chain.mine(receipt(payment));

        assertThat(item(runNow(), payment).status()).isEqualTo("MATCHED");
        assertThat(sellers.calls(payment.key())).isZero();
    }

    // --- helpers ---

    /** Both books settled, the seller credited it in full, and the chain shows the matching transfer. */
    private TestPayment credited() {
        TestPayment payment = TestPayment.random(random, AMOUNT);
        ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);
        ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED);
        ledger.record(PaymentFact.of(payment.creditNoted()), PaymentTopics.CREDIT_NOTE_ISSUED);
        chain.mine(receipt(payment));
        return payment;
    }

    private static UsdcReceipt receipt(TestPayment p) {
        String payer = p.authorization().payer();
        return new UsdcReceipt(
                p.txHash(),
                FakeChain.SAFE.number() - 10,
                true,
                List.of(new UsdcTransfer(payer, p.payTo(), AMOUNT)),
                List.of(payer + ":" + p.authorization().nonce()));
    }

    private ReconciliationReport runNow() {
        UUID runId = service.runNow().orElseThrow();
        return reports.report(runId).orElseThrow();
    }

    private static ReconciliationReport.Item item(ReconciliationReport report, TestPayment payment) {
        UUID id = PaymentProjection.paymentId(payment.key());
        return report.items().stream()
                .filter(i -> i.paymentId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("payment not in run " + report.runId()));
    }

    private int entryCount(TestPayment payment) {
        return jdbc.sql("SELECT count(*) FROM journal_entry WHERE payment_key = :key")
                .param("key", payment.key())
                .query(Integer.class)
                .single();
    }

    private long liability(TestPayment payment) {
        return jdbc.sql("""
                        SELECT sum(CASE p.side WHEN 'CREDIT' THEN p.amount_atomic ELSE -p.amount_atomic END)
                          FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                         WHERE e.payment_key = :key AND p.account_code LIKE '%:liability:customer-credits'
                        """).param("key", payment.key()).query(Long.class).single();
    }

    private List<String> mismatchKinds(TestPayment payment) {
        return jdbc.sql("SELECT kind FROM reconciliation_mismatch WHERE payment_id = :id ORDER BY kind")
                .param("id", PaymentProjection.paymentId(payment.key()))
                .query(String.class)
                .list();
    }

    private long corroborations(TestPayment payment) {
        return jdbc.sql("SELECT count(*) FROM credit_note_corroboration WHERE payment_id = :id")
                .param("id", PaymentProjection.paymentId(payment.key()))
                .query(Long.class)
                .single();
    }
}
