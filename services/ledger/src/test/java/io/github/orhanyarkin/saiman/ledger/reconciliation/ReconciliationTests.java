package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.evmrpc.UsdcTransfer;
import io.github.orhanyarkin.saiman.ledger.FakeChain;
import io.github.orhanyarkin.saiman.ledger.FakeSellerCreditNotes;
import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.api.LedgerApiGuardFilter;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentRepository;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.query.RevenueReport;
import io.github.orhanyarkin.saiman.shared.ledger.LedgerTopics;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reconciliation against {@link FakeChain} with Postgres and Kafka. A context of its own (the property only
 * changes the cache key), so the runs here see only these tests' payments.
 */
@LedgerIntegrationTest
@TestPropertySource(properties = "saiman.test.context=reconciliation")
class ReconciliationTests {

    private static final String RUNS = "/api/v1/reconciliation/runs";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** scripts/ledger-tamper-demo.sh, verbatim between BEGIN and COMMIT. */
    private static final String TAMPER_SQL = """
            WITH target AS (
              SELECT id FROM ledger.journal_entry WHERE kind = 'SALE' ORDER BY recorded_at DESC, id DESC LIMIT 1
            )
            UPDATE ledger.posting p
               SET amount_atomic = p.amount_atomic + 5000
              FROM target
             WHERE p.entry_id = target.id
            RETURNING p.entry_id, p.side, p.amount_atomic - 5000 AS was_atomic, p.amount_atomic AS now_atomic
            """;

    @Autowired
    private ReconciliationService service;

    @Autowired
    private ReconciliationReports reports;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private FakeChain chain;

    @Autowired
    private FakeSellerCreditNotes sellers;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RestTestClient client;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ConsumerFactory<String, String> consumers;

    private final SplittableRandom random = new SplittableRandom();

    @Test
    void settledPaymentWithMatchingReceiptIsMatchedAndRerunsChangeNothing() {
        TestPayment payment = settledBothBooks(20_000);
        chain.mine(receipt(payment, payment.txHash(), payment.payTo(), 20_000, true));

        ReconciliationReport.Item item = item(runNow(), payment);
        assertThat(item.status()).isEqualTo("MATCHED");
        assertThat(item.chainState()).isEqualTo("USED");
        assertThat(item.txHash()).isEqualTo(payment.txHash());
        assertThat(item.amount()).isEqualTo(Money.usdc(20_000));
        assertThat(item.mismatch()).isNull();
        assertThat(jdbc.sql("SELECT chain_block FROM payment WHERE payment_key = :key")
                        .param("key", payment.key())
                        .query(Long.class)
                        .single())
                .isEqualTo(FakeChain.SAFE.number() - 10);

        int entries = entryCount(payment);
        ReconciliationReport rerun = runNow();
        assertThat(item(rerun, payment).status()).isEqualTo("MATCHED");
        assertThat(entryCount(payment)).isEqualTo(entries);
        assertThat(mismatchKinds(payment)).isEmpty();
    }

    /**
     * ADR-0021: a credited payment (seller settled up front, then did not serve) matches its receipt like any
     * settled one once seller-api corroborates the credit note. The credit note touches no wallet, so nothing is
     * adjusted and the liability stays.
     */
    @Test
    void creditedPaymentWithMatchingReceiptIsMatched() {
        TestPayment payment = TestPayment.random(random, 20_000);
        ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);
        ledger.record(PaymentFact.of(payment.creditNoted()), PaymentTopics.CREDIT_NOTE_ISSUED);
        ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED);
        chain.mine(receipt(payment, payment.txHash(), payment.payTo(), 20_000, true));
        sellers.issue(payment.key(), payment.txHash(), 20_000); // seller-api corroborates it
        int entries = entryCount(payment);

        ReconciliationReport.Item item = item(runNow(), payment);

        assertThat(item.status()).isEqualTo("MATCHED");
        assertThat(item.sellerState()).isEqualTo("CREDITED");
        assertThat(item.mismatch()).isNull();
        assertThat(mismatchKinds(payment)).isEmpty();
        assertThat(entryCount(payment)).isEqualTo(entries);
        assertThat(jdbc.sql("""
                                SELECT sum(CASE p.side WHEN 'CREDIT' THEN p.amount_atomic ELSE -p.amount_atomic END)
                                  FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                                 WHERE e.payment_key = :key AND p.account_code LIKE '%:liability:customer-credits'
                                """).param("key", payment.key()).query(Long.class).single())
                .isEqualTo(20_000L);
    }

    @Test
    void unavailableRpcAndReceiptAboveSafeAreBothPending() {
        TestPayment down = settledBothBooks(20_000);
        chain.failReceipt(down.txHash());
        TestPayment fresh = settledBothBooks(20_000);
        chain.mine(new UsdcReceipt(
                fresh.txHash(),
                FakeChain.SAFE.number() + 1,
                true,
                List.of(new UsdcTransfer(fresh.authorization().payer(), fresh.payTo(), 20_000)),
                List.of(fresh.authorization().payer() + ":"
                        + fresh.authorization().nonce())));
        try {
            ReconciliationReport report = runNow();

            assertThat(report.status()).isEqualTo("PARTIAL");
            assertThat(item(report, down).status()).isEqualTo("PENDING");
            assertThat(item(report, fresh).status()).isEqualTo("PENDING");
            assertThat(item(report, fresh).chainState()).isEqualTo("UNKNOWN");
            assertThat(mismatchKinds(down)).isEmpty();
            assertThat(mismatchKinds(fresh)).isEmpty();
        } finally {
            chain.healReceipt(down.txHash());
            chain.mine(receipt(down, down.txHash(), down.payTo(), 20_000, true));
            chain.mine(receipt(fresh, fresh.txHash(), fresh.payTo(), 20_000, true));
        }
    }

    @Test
    void unexpectedFailureSkipsOnlyThatPaymentAndRotatesIt() {
        TestPayment broken = settledBothBooks(20_000);
        chain.mine(receipt(broken, broken.txHash(), broken.payTo(), 20_000, true));
        chain.breakReceipt(broken.txHash());
        TestPayment healthy = settledBothBooks(20_000);
        chain.mine(receipt(healthy, healthy.txHash(), healthy.payTo(), 20_000, true));
        try {
            ReconciliationReport report = runNow();

            assertThat(report.status()).isEqualTo("PARTIAL");
            assertThat(item(report, broken).status()).isEqualTo("PENDING");
            assertThat(item(report, healthy).status()).isEqualTo("MATCHED");
            assertThat(jdbc.sql("SELECT last_checked_at IS NOT NULL FROM payment WHERE payment_key = :key")
                            .param("key", broken.key())
                            .query(Boolean.class)
                            .single())
                    .isTrue();
        } finally {
            chain.healReceipt(broken.txHash());
        }
    }

    @Test
    void safeBlockFarAheadOfTheLocalClockSkipsTheChainChecks() {
        TestPayment payment = settledBothBooks(20_000);
        chain.mine(receipt(payment, payment.txHash(), payment.payTo(), 20_000, false));
        double skippedBefore = skippedSafeInFuture();
        long now = Instant.now().getEpochSecond();
        chain.overrideSafe(new ChainBlock(FakeChain.SAFE.number(), now + 3600));
        ReconciliationReport skipped;
        try {
            skipped = runNow();
        } finally {
            chain.overrideSafe(null);
        }

        assertThat(skipped.status()).isEqualTo("PARTIAL");
        assertThat(skipped.items()).isEmpty();
        assertThat(mismatchKinds(payment)).isEmpty();
        assertThat(skippedSafeInFuture()).isEqualTo(skippedBefore + 1);

        assertThat(item(runNow(), payment).mismatch().kind())
                .as("the same payment with a sane safe block")
                .isEqualTo("SETTLED_BUT_UNUSED");
    }

    @Test
    void safeBlockSlightlyAheadIsBoundedByTheLocalClockSoNothingExpiresEarly() {
        long now = Instant.now().getEpochSecond();
        TestPayment parties = TestPayment.random(random, 1);
        TestPayment payment =
                TestPayment.of(random, parties.authorization().payer(), parties.payTo(), 20_000, now + 50);
        record(payment);
        chain.mine(receipt(payment, payment.txHash(), payment.payTo(), 20_000, false));
        double skippedBefore = skippedSafeInFuture();
        // Past validBefore by the RPC's clock, not by ours; within the tolerated 60 s.
        chain.overrideSafe(new ChainBlock(FakeChain.SAFE.number(), now + 59));
        ReconciliationReport report;
        try {
            report = runNow();
        } finally {
            chain.overrideSafe(null);
        }

        assertThat(skippedSafeInFuture()).isEqualTo(skippedBefore);
        assertThat(item(report, payment).chainState()).isEqualTo("UNKNOWN");
        assertThat(mismatchKinds(payment)).containsExactly("TX_FAILED");
        assertThat(adjustmentLegs(payment)).isEmpty();
    }

    @Test
    void failedTransactionIsReportedAndBothBooksMoveToSuspense() {
        TestPayment payment = settledBothBooks(20_000);
        chain.mine(receipt(payment, payment.txHash(), payment.payTo(), 20_000, false));

        ReconciliationReport.Item item = item(runNow(), payment);

        assertThat(item.status()).isEqualTo("MISMATCH");
        assertThat(item.chainState()).isEqualTo("UNUSED");
        assertThat(item.mismatch()).isNotNull();
        assertThat(item.mismatch().kind()).isEqualTo("SETTLED_BUT_UNUSED");
        assertThat(mismatchKinds(payment)).containsExactlyInAnyOrder("TX_FAILED", "SETTLED_BUT_UNUSED");
        assertThat(adjustmentLegs(payment))
                .containsExactlyInAnyOrder(
                        "DEBIT " + wallet("buyer", payment) + ":wallet:available 20000",
                        "CREDIT platform:suspense:usdc 20000",
                        "DEBIT platform:suspense:usdc 20000",
                        "CREDIT " + wallet("seller", payment) + ":wallet 20000");
    }

    @Test
    void tamperedSaleIsAnAmountMismatchMovedToSuspenseOnce() throws Exception {
        TestPayment payment = settledBothBooks(20_000);
        chain.mine(receipt(payment, payment.txHash(), payment.payTo(), 20_000, true));
        UUID sale = jdbc.sql("SELECT id FROM journal_entry WHERE payment_key = :key AND kind = 'SALE'")
                .param("key", payment.key())
                .query(UUID.class)
                .single();
        long suspenseBefore = signedSuspense(reports.latest().orElse(null));

        List<UUID> tampered = tamper();
        assertThat(tampered).containsOnly(sale).hasSize(2);

        ReconciliationReport report = runNow();
        ReconciliationReport.Item item = item(report, payment);
        assertThat(item.status()).isEqualTo("MISMATCH");
        assertThat(item.mismatch()).isNotNull();
        assertThat(item.mismatch().kind()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(item.mismatch().ledgerValue()).isEqualTo(Money.usdc(25_000));
        assertThat(item.mismatch().chainValue()).isEqualTo(Money.usdc(20_000));
        assertThat(item.mismatch().adjustmentEntryId()).isNotNull();
        assertThat(adjustmentLegs(payment))
                .containsExactlyInAnyOrder(
                        "DEBIT platform:suspense:usdc 5000", "CREDIT " + wallet("seller", payment) + ":wallet 5000");
        assertThat(signedSuspense(report) - suspenseBefore).isEqualTo(5_000);
        assertThat(report.suspenseSide()).isEqualTo("DEBIT");

        PaymentProjection projection = payments.findByKey(payment.key()).orElseThrow();
        List<String> published =
                drain(LedgerTopics.RECONCILIATION_MISMATCH, projection.id().toString());
        assertThat(published).singleElement().satisfies(json -> {
            assertThat(json).contains("\"kind\":\"AMOUNT_MISMATCH\"");
            assertThat(json)
                    .contains("\"adjustmentEntryId\":\"" + item.mismatch().adjustmentEntryId() + "\"");
            assertThat(json).doesNotContain(payment.authorization().nonce().toLowerCase(Locale.ROOT));
            JsonNode tree = jsonMapper.readTree(json);
            assertThat(tree.findValues("nonce")).isEmpty();
            assertThat(tree.findValues("authorization")).isEmpty();
        });

        int entries = entryCount(payment);
        ReconciliationReport rerun = runNow();
        assertThat(item(rerun, payment).status()).isEqualTo("MATCHED");
        assertThat(entryCount(payment)).isEqualTo(entries);
        assertThat(mismatchKinds(payment)).containsExactly("AMOUNT_MISMATCH");
        assertThat(signedSuspense(rerun)).isEqualTo(signedSuspense(report));
    }

    @Test
    void realSettledPaymentIsCheckedInTheFirstRunDespiteAFloodOfForgedRows() {
        TestPayment real = settledBothBooks(20_000);
        chain.mine(receipt(real, real.txHash(), real.payTo(), 20_000, true));
        List<TestPayment> flood = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            TestPayment forged = TestPayment.random(random, 1);
            ledger.record(PaymentFact.of(forged.authorized()), PaymentTopics.AUTHORIZED);
            flood.add(forged);
        }

        ReconciliationReport report = runNow();

        assertThat(item(report, real).status()).isEqualTo("MATCHED");
        assertThat(report.items()).hasSizeLessThanOrEqualTo(50);
        // The rest of the batch went to the flood, oldest first: the first forged row is in this run too.
        assertThat(item(report, flood.getFirst()).status()).isNotNull();
    }

    @Test
    void nearMaxValidBeforeDoesNotFailARun() {
        TestPayment payment = TestPayment.of(
                random,
                TestPayment.address(random),
                TestPayment.address(random),
                20_000,
                AuthorizationRef.MAX_VALID_BEFORE - 1);
        ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);

        ReconciliationReport report = runNow();

        assertThat(report.status()).isNotEqualTo("FAILED");
        assertThat(item(report, payment).status()).isEqualTo("PENDING");
    }

    /**
     * M5 audit: a forged seller fact names a real, used authorization with a transaction hash that has no receipt,
     * and the log search finds nothing. Reconciliation records chain USED but no canonical receipt (chain_tx_hash
     * stays null) and a TX_NOT_FOUND finding, so the sale is not chain-verified revenue.
     */
    @Test
    void usedAuthorizationWithoutAMatchedReceiptIsNotVerifiedRevenue() {
        TestPayment forged = settledBothBooks(20_000);
        chain.markUsedWithoutLog(
                forged.authorization().payer(), forged.authorization().nonce());

        ReconciliationReport.Item item = item(runNow(), forged);

        assertThat(item.chainState()).isEqualTo("USED");
        assertThat(item.status()).isEqualTo("MISMATCH");
        assertThat(mismatchKinds(forged)).containsExactly("TX_NOT_FOUND");
        assertThat(chainTxHash(forged)).isNull();
        assertUnverified(forged);
    }

    /**
     * A row from before the seller-hash rule (or a buyer-only CHAIN resolution) has no reported hash at all: a used
     * authorization the log search misses is TX_UNKNOWN, which records no finding. Without a canonical receipt it must
     * still not count as chain-verified revenue.
     */
    @Test
    void txUnknownSaleWithoutAFindingIsNotVerifiedRevenue() {
        TestPayment legacy = settledBothBooks(30_000);
        jdbc.sql("UPDATE payment SET buyer_tx_hash = NULL, seller_tx_hash = NULL WHERE payment_key = :key")
                .param("key", legacy.key())
                .update();
        chain.markUsedWithoutLog(
                legacy.authorization().payer(), legacy.authorization().nonce());

        ReconciliationReport.Item item = item(runNow(), legacy);

        assertThat(item.status()).isEqualTo("TX_UNKNOWN");
        assertThat(item.chainState()).isEqualTo("USED");
        assertThat(mismatchKinds(legacy)).isEmpty();
        assertThat(chainTxHash(legacy)).isNull();
        assertUnverified(legacy);
    }

    @Test
    void secondRunWhileOneHoldsTheLockIsRefusedWith409() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            try (PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
                lock.setLong(1, ReconciliationService.LOCK_KEY);
                lock.execute();
            }
            post(RUNS)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.detail")
                    .isEqualTo("A reconciliation run is already in progress")
                    .jsonPath("$.instance")
                    .isEqualTo("/api/v1/reconciliation/runs");
            assertThat(service.runNow()).isEmpty();
            try (PreparedStatement unlock = other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                unlock.setLong(1, ReconciliationService.LOCK_KEY);
                unlock.execute();
            }
        }
    }

    @Test
    void postStartsAnAsyncRunAndASecondPostWhileItRunsIs409() throws Exception {
        CountDownLatch inside = chain.closeGate();
        UUID runId;
        try {
            Map<String, Object> body = post(RUNS)
                    .exchange()
                    .expectStatus()
                    .isAccepted()
                    .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .returnResult()
                    .getResponseBody();
            assertThat(body).containsOnlyKeys("runId");
            runId = UUID.fromString((String) body.get("runId"));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            post(RUNS).exchange().expectStatus().isEqualTo(409);
            ReconciliationReport running = getReport(RUNS + "/" + runId);
            assertThat(running.status()).isEqualTo("RUNNING");
        } finally {
            chain.openGate();
        }
        await().atMost(TIMEOUT)
                .until(() -> !"RUNNING".equals(getReport(RUNS + "/" + runId).status()));
        ReconciliationReport latest = getReport(RUNS + "/latest");
        assertThat(latest.runId()).isEqualTo(runId);
        assertThat(latest.status()).isEqualTo("COMPLETED");
        assertThat(latest.network()).isEqualTo("eip155:84532");
        assertThat(latest.safeBlock()).isEqualTo(FakeChain.SAFE.number());

        // The run is over, but the previous manual start was less than min-manual-interval (30 s) ago.
        post(RUNS)
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .exists("Retry-After")
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(429)
                .jsonPath("$.detail")
                .isEqualTo("A reconciliation run was started recently; retry later")
                .jsonPath("$.instance")
                .isEqualTo("/api/v1/reconciliation/runs");
    }

    @Test
    void postIsGuarded() {
        client.post()
                .uri(RUNS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri(RUNS)
                .header(LedgerApiGuardFilter.CSRF_HEADER, "1")
                .contentType(MediaType.TEXT_PLAIN)
                .body("{}")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri(RUNS + "/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    // --- helpers ---

    private TestPayment settledBothBooks(long amount) {
        return record(TestPayment.random(random, amount));
    }

    private TestPayment record(TestPayment payment) {
        ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);
        ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED);
        return payment;
    }

    private static UsdcReceipt receipt(TestPayment p, String tx, String to, long value, boolean succeeded) {
        String payer = p.authorization().payer();
        return new UsdcReceipt(
                tx,
                FakeChain.SAFE.number() - 10,
                succeeded,
                succeeded ? List.of(new UsdcTransfer(payer, to, value)) : List.of(),
                succeeded ? List.of(payer + ":" + p.authorization().nonce()) : List.of());
    }

    private double skippedSafeInFuture() {
        Counter counter = meters.find("saiman.ledger.reconciliation.skipped")
                .tag("reason", "safe_in_future")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private ReconciliationReport runNow() {
        UUID runId = service.runNow().orElseThrow();
        return reports.report(runId).orElseThrow();
    }

    private ReconciliationReport.Item item(ReconciliationReport report, TestPayment payment) {
        UUID id = PaymentProjection.paymentId(payment.key());
        return report.items().stream()
                .filter(i -> i.paymentId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("payment not in run " + report.runId()));
    }

    private @Nullable String chainTxHash(TestPayment payment) {
        return jdbc.sql("SELECT chain_tx_hash FROM payment WHERE payment_key = :key")
                .param("key", payment.key())
                .query((rs, n) -> Optional.ofNullable(rs.getString(1)))
                .single()
                .orElse(null);
    }

    /** The seller's revenue row counts the sale per books only. */
    private void assertUnverified(TestPayment payment) {
        RevenueReport revenue = client.get()
                .uri("/api/v1/ledger/revenue?payTo=" + payment.payTo())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(RevenueReport.class)
                .returnResult()
                .getResponseBody();
        assertThat(revenue).isNotNull();
        assertThat(revenue.items()).singleElement().satisfies(s -> {
            assertThat(s.grossSales().atomicUnits()).isEqualTo(payment.amount().atomicUnits());
            assertThat(s.chainVerified().grossSales().atomicUnits()).isZero();
            assertThat(s.unverifiedGrossSales().atomicUnits())
                    .isEqualTo(payment.amount().atomicUnits());
        });
    }

    private int entryCount(TestPayment payment) {
        return jdbc.sql("SELECT count(*) FROM journal_entry WHERE payment_key = :key")
                .param("key", payment.key())
                .query(Integer.class)
                .single();
    }

    private List<String> mismatchKinds(TestPayment payment) {
        return jdbc.sql("SELECT kind FROM reconciliation_mismatch WHERE payment_id = :id ORDER BY kind")
                .param("id", PaymentProjection.paymentId(payment.key()))
                .query(String.class)
                .list();
    }

    private List<String> adjustmentLegs(TestPayment payment) {
        return jdbc.sql("""
                        SELECT p.side || ' ' || p.account_code || ' ' || p.amount_atomic
                          FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                         WHERE e.payment_key = :key AND e.kind = 'ADJUSTMENT'
                        """).param("key", payment.key()).query(String.class).list();
    }

    private static String wallet(String book, TestPayment payment) {
        String address = book.equals("buyer") ? payment.authorization().payer() : payment.payTo();
        return book + ":" + address.toLowerCase(Locale.ROOT);
    }

    private static long signedSuspense(ReconciliationReport report) {
        if (report == null) {
            return 0;
        }
        long abs = report.suspense().atomicUnits();
        return "CREDIT".equals(report.suspenseSide()) ? -abs : abs;
    }

    /** Runs the tamper script's transaction as a superuser would; returns the entry ids it touched. */
    private List<UUID> tamper() throws Exception {
        List<UUID> touched = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var set = c.createStatement()) {
                set.execute("SET LOCAL session_replication_role = replica");
            }
            try (var update = c.prepareStatement(TAMPER_SQL);
                    var rs = update.executeQuery()) {
                while (rs.next()) {
                    touched.add(rs.getObject("entry_id", UUID.class));
                }
            }
            c.commit();
        }
        return touched;
    }

    private RestTestClient.RequestHeadersSpec<?> post(String uri) {
        return client.post()
                .uri(uri)
                .header(LedgerApiGuardFilter.CSRF_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}");
    }

    private ReconciliationReport getReport(String uri) {
        return client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ReconciliationReport.class)
                .returnResult()
                .getResponseBody();
    }

    private List<String> drain(String topic, String key) {
        List<String> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(r.key())) {
                        found.add(r.value());
                    }
                }
                return !found.isEmpty();
            });
        }
        return found;
    }
}
