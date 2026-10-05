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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M4b audit (ADR-0021): a credit note touches no wallet, so the chain cannot expose a forged {@code CreditNoteIssued}.
 * Reconciliation asks seller-api ({@link FakeSellerCreditNotes} here) and reports {@code CREDIT_NOTE_UNCORROBORATED}
 * instead of MATCHED; a seller that cannot answer leaves the item PENDING. Same context as {@link ReconciliationTests}.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = "saiman.test.context=reconciliation")
@ExtendWith(OutputCaptureExtension.class)
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

    @Autowired
    private ConsumerFactory<String, String> consumers;

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

    @Test
    void aNewFindingIsPublishedOnceWithNullChainFieldsAndARerunPublishesNothing() throws IOException {
        TestPayment payment = credited();
        UUID paymentId = PaymentProjection.paymentId(payment.key());

        ReconciliationReport first = runNow();
        assertThat(item(first, payment).status()).isEqualTo("MISMATCH");
        List<ConsumerRecord<String, String>> records = drain(paymentId.toString(), 1, Duration.ofSeconds(30));
        assertThat(records).hasSize(1);

        JsonNode event = JSON.readTree(records.getFirst().value());
        JsonNode fixture = JSON.readTree(Files.readString(MISMATCH_FIXTURE, StandardCharsets.UTF_8));
        assertThat(fieldNames(event)).isEqualTo(fieldNames(fixture));
        assertThat(fieldNames(event.get("meta"))).isEqualTo(fieldNames(fixture.get("meta")));
        assertThat(event.get("kind").asString()).isEqualTo(fixture.get("kind").asString());
        assertThat(event.get("meta").get("producer"))
                .isEqualTo(fixture.get("meta").get("producer"));
        assertThat(event.get("paymentId").asString()).isEqualTo(paymentId.toString());
        assertThat(event.get("reconciliationRunId").asString())
                .isEqualTo(first.runId().toString());
        String mismatchId = ReconciliationService.mismatchId(paymentId, "CREDIT_NOTE_UNCORROBORATED")
                .toString();
        assertThat(event.get("mismatchId").asString()).isEqualTo(mismatchId);
        assertThat(event.get("meta").get("eventId").asString()).isEqualTo(mismatchId);
        assertThat(event.get("ledgerAmount"))
                .isEqualTo(JSON.readTree("{\"atomicUnits\":" + AMOUNT + ",\"asset\":\"USDC\",\"decimals\":6}"));
        assertThat(event.get("reportedTxHash").asString()).isEqualToIgnoringCase(payment.txHash());
        for (String field : List.of("chainAmount", "chainTxHash", "adjustmentEntryId")) {
            assertThat(event.get(field).isNull()).as(field).isTrue();
        }

        ReconciliationReport second = runNow();
        assertThat(item(second, payment).status()).isEqualTo("MISMATCH");
        assertThat(mismatchKinds(payment)).containsExactly("CREDIT_NOTE_UNCORROBORATED");
        // The second run found the row already there, so it published nothing (a fixed window: there is no sentinel
        // whose arrival would prove the absence, records of other keys may sit on other partitions).
        assertThat(drain(paymentId.toString(), 2, Duration.ofSeconds(5))).hasSize(1);
    }

    @Test
    void aRefusedServiceTokenLeavesPaymentsPendingAndLogsOncePerRun(CapturedOutput output) {
        TestPayment first = credited();
        TestPayment second = credited();
        sellers.unauthorized(first.key());
        sellers.unauthorized(second.key());

        ReconciliationReport report;
        try {
            report = runNow();
        } finally {
            // Shared context: later runs must find these corroborated, not unanswerable (PARTIAL).
            for (TestPayment p : List.of(first, second)) {
                sellers.up(p.key());
                sellers.issue(p.key(), p.txHash(), AMOUNT);
            }
        }

        assertThat(item(report, first).status()).isEqualTo("PENDING");
        assertThat(item(report, second).status()).isEqualTo("PENDING");
        assertThat(mismatchKinds(first)).isEmpty();
        String marker = "Reconciliation run " + report.runId() + ": seller-api refused the ledger's service token";
        assertThat(output.getAll().split(java.util.regex.Pattern.quote(marker), -1))
                .as("one ERROR per run")
                .hasSize(2);
    }

    // --- helpers ---

    /** The contract's golden fixture (libs/shared), relative to this project's directory. */
    private static final Path MISMATCH_FIXTURE = Path.of("../../libs/shared/src/test/resources/fixtures/events/"
            + "ledger.reconciliation-mismatch.v1.CREDIT_NOTE_UNCORROBORATED.json");

    private static final String MISMATCH_TOPIC = "ledger.reconciliation-mismatch.v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Set<String> fieldNames(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    /** Reads the mismatch topic from the start until {@code expected} records with {@code key} arrived or time is up. */
    private List<ConsumerRecord<String, String>> drain(String key, int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of(MISMATCH_TOPIC));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

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
