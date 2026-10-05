package io.github.orhanyarkin.saiman.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts;
import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.JournalRepository;
import io.github.orhanyarkin.saiman.ledger.journal.LedgerBook;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.query.PaymentDetail;
import io.github.orhanyarkin.saiman.ledger.query.PaymentPage;
import io.github.orhanyarkin.saiman.ledger.query.PaymentSummary;
import io.github.orhanyarkin.saiman.ledger.query.RevenueReport;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationRunList;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dashboard's read endpoints (M5, ADR-0022): payments list and drill-down, revenue, reconciliation history and
 * the typed POST. The context is shared with every other ledger integration test, so each test filters by its own
 * agent run id, payTo or reconciliation run ids.
 */
@LedgerIntegrationTest
class LedgerReadApiTests {

    private static final String PAYMENTS = "/api/v1/ledger/payments";
    private static final String REVENUE = "/api/v1/ledger/revenue";
    private static final String RUNS = "/api/v1/reconciliation/runs";

    @Autowired
    private RestTestClient client;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JournalRepository journal;

    @Autowired
    private TransactionTemplate transactions;

    @LocalServerPort
    private int port;

    private final SplittableRandom random = new SplittableRandom();

    @Test
    void paymentsOfARunShowTheThreeBookStatesWithoutKeyMaterial() {
        UUID runId = UUID.randomUUID();
        TestPayment settled = inRun(TestPayment.random(random, 10_000), runId);
        TestPayment credited = inRun(TestPayment.random(random, 20_000), runId);
        settle(settled);
        credit(credited);

        Map<String, Object> page = getMap(PAYMENTS + "?runId=" + runId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(page).containsOnlyKeys("items", "nextCursor");
        assertThat(page.get("nextCursor")).isNull();
        assertThat(items).hasSize(2);
        assertThat(items.getFirst())
                .containsOnlyKeys(
                        "paymentId",
                        "runId",
                        "buyerState",
                        "sellerState",
                        "chainState",
                        "amount",
                        "payTo",
                        "createdAt",
                        "updatedAt",
                        "buyerTxHash",
                        "sellerTxHash",
                        "chainTxHash");
        // Newest first: the credited payment was recorded last.
        assertThat(items.getFirst())
                .containsEntry("runId", runId.toString())
                .containsEntry("buyerState", "SETTLED")
                .containsEntry("sellerState", "CREDITED")
                .containsEntry("chainState", "UNKNOWN")
                .containsEntry("payTo", credited.payTo().toLowerCase(Locale.ROOT))
                .containsEntry("amount", Map.of("atomicUnits", 20_000, "asset", "USDC", "decimals", 6))
                .containsEntry("sellerTxHash", credited.txHash())
                .containsEntry("chainTxHash", null);
        assertThat(items.get(1)).containsEntry("sellerState", "SETTLED");
    }

    @Test
    void keysetPagingCoversEveryPaymentOnceAcrossThreePages() {
        UUID runId = UUID.randomUUID();
        for (int i = 0; i < 7; i++) {
            settle(inRun(TestPayment.random(random, 1_000 + i), runId));
        }

        List<PaymentSummary> seen = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        String cursor = null;
        do {
            String uri = PAYMENTS + "?runId=" + runId + "&limit=3" + (cursor == null ? "" : "&before=" + cursor);
            PaymentPage page = get(uri, PaymentPage.class);
            sizes.add(page.items().size());
            seen.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(sizes).containsExactly(3, 3, 1);
        assertThat(seen)
                .extracting(PaymentSummary::paymentId)
                .doesNotHaveDuplicates()
                .hasSize(7);
        assertThat(seen)
                .isSortedAccordingTo(Comparator.comparing(PaymentSummary::createdAt)
                        .thenComparing(PaymentSummary::paymentId)
                        .reversed());
        assertThat(seen)
                .extracting(p -> p.amount().atomicUnits())
                .containsExactlyInAnyOrder(1_000L, 1_001L, 1_002L, 1_003L, 1_004L, 1_005L, 1_006L);
    }

    @Test
    void bookFilterKeepsPaymentsWithEntriesInThatBook() {
        UUID runId = UUID.randomUUID();
        TestPayment buyerOnly = inRun(TestPayment.random(random, 3_000), runId);
        TestPayment both = inRun(TestPayment.random(random, 4_000), runId);
        record(buyerOnly.authorized(), PaymentTopics.AUTHORIZED);
        record(buyerOnly.buyerSettled(), PaymentTopics.SETTLED);
        settle(both);

        PaymentPage sellers = get(PAYMENTS + "?runId=" + runId + "&book=SELLER", PaymentPage.class);
        PaymentPage buyers = get(PAYMENTS + "?runId=" + runId + "&book=BUYER", PaymentPage.class);

        assertThat(sellers.items())
                .singleElement()
                .satisfies(p -> assertThat(p.amount().atomicUnits()).isEqualTo(4_000));
        assertThat(buyers.items()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                PAYMENTS + "?limit=0",
                PAYMENTS + "?limit=101",
                PAYMENTS + "?limit=abc",
                PAYMENTS + "?book=PLATFORM",
                PAYMENTS + "?book=seller",
                PAYMENTS + "?runId=not-a-uuid",
                PAYMENTS + "?before=garbage!",
                PAYMENTS + "?before=MTIzOmFiYw",
                PAYMENTS + "/not-a-uuid",
                RUNS + "?limit=0",
                RUNS + "?limit=101"
            })
    void invalidParametersAreProblemDetails400(String uri) {
        client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400);
    }

    /**
     * Every path variable and query parameter of the service's {@code /api/**} endpoints, given a marker value
     * (plain, and in query parameters also percent-encoded with spaces, quotes, angle brackets and a newline): a 400
     * whose {@code detail} names the parameter only and whose body nowhere contains the marker. Path variables carry
     * only the plain marker because the guard refuses {@code %} in the path.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                PAYMENTS + "?runId=$",
                PAYMENTS + "?book=$",
                PAYMENTS + "?limit=$",
                PAYMENTS + "?before=$",
                PAYMENTS + "/$",
                REVENUE + "?payTo=$",
                RUNS + "?limit=$",
                RUNS + "/$"
            })
    void invalidValuesAreNeverEchoed(String template) {
        String name = template.contains("?")
                ? template.substring(template.indexOf('?') + 1, template.indexOf('='))
                : template.startsWith(PAYMENTS) ? "paymentId" : "id";
        List<String> markers = new ArrayList<>(List.of("ZZMARK"));
        if (template.contains("?")) {
            markers.add("ZZMARK%20%22quoted%22%27%3Cb%3E%0Anext%20line");
        }
        for (String marker : markers) {
            // Absolute, so the client sends the percent-encoding as written instead of encoding the % again.
            URI uri = URI.create("http://localhost:" + port + template.replace("$", marker));
            String body = client.get()
                    .uri(uri)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(body).as(template).isNotNull().doesNotContain("ZZMARK");
            client.get().uri(uri).exchange().expectBody().jsonPath("$.detail").isEqualTo(name + " is invalid");
        }
    }

    /** Cursors outside [2025-01-01, now + 1 day], or too large for a long, are the documented 400, not a 500. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "9400000000000000000", // ~year 300000: beyond a long of micros
                "9223372036854775807", // Long.MAX_VALUE micros (~year 294247)
                "-9223372036854775808",
                "-1000000",
                "0",
                "1704067200000000", // 2024-01-01
                "4102444800000000" // 2100-01-01
            })
    void outOfRangeCursorsAre400(String micros) {
        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((micros + ":" + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII));
        client.get()
                .uri(PAYMENTS + "?before=" + token)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("before is invalid");
    }

    @Test
    void unknownPaymentIs404() {
        client.get()
                .uri(PAYMENTS + "/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("No such payment");
    }

    @Test
    void drillDownShowsEveryEntryBalancedAndTheFindings() {
        UUID runId = UUID.randomUUID();
        TestPayment credited = inRun(TestPayment.random(random, 25_000), runId);
        credit(credited);
        UUID paymentId = onlyPayment(runId).paymentId();
        jdbc.sql("""
                        INSERT INTO reconciliation_mismatch (id, payment_id, kind, ledger_amount_atomic,
                                                             chain_amount_atomic, asset, decimals)
                        VALUES (:id, :paymentId, 'CREDIT_NOTE_UNCORROBORATED', 25000, 0, 'USDC', 6)
                        """)
                .param("id", UUID.randomUUID())
                .param("paymentId", paymentId)
                .update();

        PaymentDetail detail = get(PAYMENTS + "/" + paymentId, PaymentDetail.class);

        assertThat(detail.payment().paymentId()).isEqualTo(paymentId);
        assertThat(detail.payment().sellerState()).isEqualTo("CREDITED");
        assertThat(detail.entries())
                .extracting(e -> e.book() + ":" + e.kind())
                .contains("BUYER:SETTLE", "SELLER:SALE", "SELLER:CREDIT_NOTE");
        String payTo = credited.payTo().toLowerCase(Locale.ROOT);
        assertThat(detail.entries())
                .filteredOn(e -> e.kind().equals("CREDIT_NOTE"))
                .singleElement()
                .satisfies(e -> assertThat(e.postings())
                        .extracting(l -> l.side() + " " + l.accountCode() + " "
                                + l.amount().atomicUnits())
                        .containsExactlyInAnyOrder(
                                "DEBIT seller:" + payTo + ":revenue:credit-notes 25000",
                                "CREDIT seller:" + payTo + ":liability:customer-credits 25000"));
        for (PaymentDetail.Entry entry : detail.entries()) {
            long net = entry.postings().stream()
                    .mapToLong(l -> l.side().equals("DEBIT")
                            ? l.amount().atomicUnits()
                            : -l.amount().atomicUnits())
                    .sum();
            assertThat(net).as("entry %s balances", entry.entryId()).isZero();
        }
        assertThat(detail.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo("CREDIT_NOTE_UNCORROBORATED");
            assertThat(m.status()).isEqualTo("REPORTED");
            assertThat(m.reconciliationRunId()).isNull();
            assertThat(m.detectedAt()).isNotNull();
            assertThat(m.ledgerValue())
                    .isNotNull()
                    .satisfies(v -> assertThat(v.atomicUnits()).isEqualTo(25_000));
        });
    }

    @Test
    void revenueShowsGrossCreditNotesNetAndCustomerCredits() {
        String payTo = TestPayment.address(random);
        settle(TestPayment.of(random, TestPayment.address(random), payTo, 10_000));
        settle(TestPayment.of(random, TestPayment.address(random), payTo, 5_000));
        credit(TestPayment.of(random, TestPayment.address(random), payTo, 7_000));
        String creditedOnly = TestPayment.address(random);
        credit(TestPayment.of(random, TestPayment.address(random), creditedOnly, 9_000));

        assertThat(revenueOf(payTo)).satisfies(s -> {
            assertThat(s.payTo()).isEqualTo(payTo.toLowerCase(Locale.ROOT));
            assertThat(s.grossSales().atomicUnits()).isEqualTo(22_000);
            assertThat(s.creditNotes().atomicUnits()).isEqualTo(7_000);
            assertThat(s.netRevenue().atomicUnits()).isEqualTo(15_000);
            assertThat(s.customerCredits().atomicUnits()).isEqualTo(7_000);
            assertThat(s.sales()).isEqualTo(3);
            assertThat(s.credited()).isEqualTo(1);
            assertThat(s.grossSales().asset()).isEqualTo("USDC");
            assertThat(s.netRevenue().decimals()).isEqualTo(6);
            // Nothing reconciled yet: all of it is per books only.
            assertThat(s.chainVerified().grossSales().atomicUnits()).isZero();
            assertThat(s.unverifiedGrossSales().atomicUnits()).isEqualTo(22_000);
            assertThat(s.openFindings()).isZero();
            assertThat(s.saturated()).isFalse();
        });
        assertThat(revenueOf(creditedOnly)).satisfies(s -> {
            assertThat(s.grossSales().atomicUnits()).isEqualTo(9_000);
            assertThat(s.creditNotes().atomicUnits()).isEqualTo(9_000);
            assertThat(s.netRevenue().atomicUnits()).isZero();
            assertThat(s.customerCredits().atomicUnits()).isEqualTo(9_000);
            assertThat(s.sales()).isEqualTo(1);
            assertThat(s.credited()).isEqualTo(1);
        });
    }

    /**
     * A forged seller-only SALE (no buyer fact) that reconciliation found UNUSED on chain stays in the per-books gross
     * (ADJUSTMENT never touches revenue accounts) but not in the chain-verified part, and it is an open finding.
     */
    @Test
    void forgedSaleIsPerBooksOnlyAndAnOpenFinding() {
        String payTo = TestPayment.address(random);
        TestPayment genuine = TestPayment.of(random, TestPayment.address(random), payTo, 40_000);
        settle(genuine);
        chainUsed(genuine);
        TestPayment forged = TestPayment.of(random, TestPayment.address(random), payTo, 900_000);
        record(forged.sellerSettled(), PaymentTopics.SETTLED);
        jdbc.sql("UPDATE payment SET chain_state = 'UNUSED' WHERE payment_key = :key")
                .param("key", forged.key())
                .update();
        finding(forged, "SETTLED_BUT_UNUSED");

        RevenueReport.Seller s = revenueOf(payTo);

        assertThat(s.grossSales().atomicUnits()).isEqualTo(940_000);
        assertThat(s.netRevenue().atomicUnits()).isEqualTo(940_000);
        assertThat(s.chainVerified().grossSales().atomicUnits()).isEqualTo(40_000);
        assertThat(s.chainVerified().netRevenue().atomicUnits()).isEqualTo(40_000);
        assertThat(s.unverifiedGrossSales().atomicUnits()).isEqualTo(900_000);
        assertThat(s.openFindings()).isEqualTo(1);
        assertThat(s.sales()).isEqualTo(2);
    }

    /** A sale whose chain transfer exists but whose amount differs is not verified either. */
    @Test
    void usedPaymentWithAChainFindingIsNotVerified() {
        String payTo = TestPayment.address(random);
        TestPayment p = TestPayment.of(random, TestPayment.address(random), payTo, 12_000);
        settle(p);
        chainUsed(p);
        finding(p, "AMOUNT_MISMATCH");

        RevenueReport.Seller s = revenueOf(payTo);

        assertThat(s.chainVerified().grossSales().atomicUnits()).isZero();
        assertThat(s.unverifiedGrossSales().atomicUnits()).isEqualTo(12_000);
        assertThat(s.openFindings()).isEqualTo(1);
    }

    /**
     * A credit note seller-api never confirmed (CREDIT_NOTE_UNCORROBORATED) lowers the per-books net but not the
     * chain-verified one; a corroborated credit note lowers both.
     */
    @Test
    void uncorroboratedCreditNoteIsPerBooksOnly() {
        String payTo = TestPayment.address(random);
        TestPayment unconfirmed = TestPayment.of(random, TestPayment.address(random), payTo, 30_000);
        credit(unconfirmed);
        chainUsed(unconfirmed);
        finding(unconfirmed, "CREDIT_NOTE_UNCORROBORATED");
        TestPayment confirmed = TestPayment.of(random, TestPayment.address(random), payTo, 5_000);
        credit(confirmed);
        chainUsed(confirmed);
        corroborated(confirmed);

        RevenueReport.Seller s = revenueOf(payTo);

        assertThat(s.grossSales().atomicUnits()).isEqualTo(35_000);
        assertThat(s.creditNotes().atomicUnits()).isEqualTo(35_000);
        assertThat(s.netRevenue().atomicUnits()).isZero();
        // Both sales happened on chain; only the confirmed credit note counts as verified.
        assertThat(s.chainVerified().grossSales().atomicUnits()).isEqualTo(35_000);
        assertThat(s.chainVerified().creditNotes().atomicUnits()).isEqualTo(5_000);
        assertThat(s.chainVerified().netRevenue().atomicUnits()).isEqualTo(30_000);
        assertThat(s.unverifiedGrossSales().atomicUnits()).isZero();
        assertThat(s.openFindings()).isEqualTo(1);
    }

    /**
     * There is no domain path that reverses a CREDIT_NOTE (ADR-0021: a human posts it), so the test posts the REVERSAL
     * through {@link JournalRepository} directly: the nets must drop back, which one-sided sums never did.
     */
    @Test
    void reversalOfACreditNoteReducesCreditNotesAndCustomerCredits() {
        String payTo = TestPayment.address(random);
        TestPayment p = TestPayment.of(random, TestPayment.address(random), payTo, 8_000);
        credit(p);
        UUID paymentId = paymentId(p);
        UUID creditNoteId = jdbc.sql(
                        "SELECT id FROM journal_entry WHERE payment_id = :id AND book = 'SELLER' AND kind = 'CREDIT_NOTE'")
                .param("id", paymentId)
                .query(UUID.class)
                .single();
        Money amount = Money.usdc(8_000);
        JournalEntry reversal = new JournalEntry(
                UUID.randomUUID(),
                paymentId,
                p.key(),
                LedgerBook.SELLER,
                EntryKind.REVERSAL,
                null,
                creditNoteId,
                "Credit note reversed by an operator",
                Instant.now(),
                List.of(
                        Posting.credit(ChartOfAccounts.sellerCreditNotes(payTo), amount),
                        Posting.debit(ChartOfAccounts.sellerCustomerCredits(payTo), amount)));
        transactions.executeWithoutResult(tx -> journal.post(reversal));

        RevenueReport.Seller s = revenueOf(payTo);

        assertThat(s.grossSales().atomicUnits()).isEqualTo(8_000);
        assertThat(s.creditNotes().atomicUnits()).isZero();
        assertThat(s.netRevenue().atomicUnits()).isEqualTo(8_000);
        assertThat(s.customerCredits().atomicUnits()).isZero();
    }

    /** Sums above 2^53-1 clamp there (not at Long.MAX_VALUE) and say so. */
    @Test
    void revenueSaturatesAtTheJsonSafeIntegerAndFlagsIt() {
        String payTo = TestPayment.address(random);
        long max = 9_007_199_254_740_991L;
        settle(TestPayment.of(random, TestPayment.address(random), payTo, max));
        settle(TestPayment.of(random, TestPayment.address(random), payTo, max));

        RevenueReport.Seller s = revenueOf(payTo);

        assertThat(s.grossSales().atomicUnits()).isEqualTo(max);
        assertThat(s.netRevenue().atomicUnits()).isEqualTo(max);
        assertThat(s.unverifiedGrossSales().atomicUnits()).isEqualTo(max);
        assertThat(s.saturated()).isTrue();
    }

    /** payTo can be forged on Kafka, so the unfiltered report is capped at 100 rows, largest gross first. */
    @Test
    void revenueIsCappedAt100RowsAndSaysSo() {
        for (int i = 0; i < 101; i++) {
            settle(TestPayment.of(random, TestPayment.address(random), TestPayment.address(random), 1));
        }

        RevenueReport report = get(REVENUE, RevenueReport.class);

        assertThat(report.items()).hasSize(100);
        assertThat(report.truncated()).isTrue();
        assertThat(report.items())
                .isSortedAccordingTo(Comparator.comparingLong(
                                (RevenueReport.Seller s) -> s.grossSales().atomicUnits())
                        .reversed());
    }

    @Test
    void revenueRowsHaveTheDocumentedShape() {
        String payTo = TestPayment.address(random);
        settle(TestPayment.of(random, TestPayment.address(random), payTo, 1_234));

        Map<String, Object> page = getMap(REVENUE + "?payTo=" + payTo);

        assertThat(page).containsOnlyKeys("items", "truncated");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) page.get("items")).getFirst();
        assertThat(row)
                .containsOnlyKeys(
                        "payTo",
                        "grossSales",
                        "creditNotes",
                        "netRevenue",
                        "customerCredits",
                        "sales",
                        "credited",
                        "chainVerified",
                        "unverifiedGrossSales",
                        "openFindings",
                        "saturated");
        assertThat(row.get("chainVerified"))
                .isEqualTo(Map.of(
                        "grossSales", Map.of("atomicUnits", 0, "asset", "USDC", "decimals", 6),
                        "creditNotes", Map.of("atomicUnits", 0, "asset", "USDC", "decimals", 6),
                        "netRevenue", Map.of("atomicUnits", 0, "asset", "USDC", "decimals", 6)));
    }

    /**
     * Property-style (ADR-0019 approach: seeded random cases, seed in the failure message): for random sellers with
     * random mixes of settled and credited payments, some of them seen on chain and some credit notes corroborated,
     * every per-books figure equals both the sum of what was booked and the trial balance's totals of the same
     * accounts, and the chain-verified figures equal the sums over the verified subset (so verified is within per
     * books).
     */
    @Test
    void revenueEqualsTheTrialBalanceAndTheBookedAmounts() {
        long seed = random.nextLong();
        SplittableRandom cases = new SplittableRandom(seed);
        // gross, creditNotes, sales, credited, verifiedGross, verifiedCreditNotes
        Map<String, long[]> expected = new java.util.HashMap<>();
        for (int s = 0; s < 4; s++) {
            String payTo = TestPayment.address(cases);
            long[] totals = new long[6];
            for (int p = 1 + cases.nextInt(4); p > 0; p--) {
                long amount = 2 + cases.nextLong(1_000_000_000L);
                TestPayment payment = TestPayment.of(cases, TestPayment.address(cases), payTo, amount);
                boolean used = cases.nextBoolean();
                totals[0] += amount;
                totals[2]++;
                if (cases.nextBoolean()) {
                    credit(payment);
                    totals[1] += amount;
                    totals[3]++;
                    if (used && cases.nextBoolean()) {
                        corroborated(payment);
                        totals[5] += amount;
                    }
                } else {
                    settle(payment);
                }
                if (used) {
                    chainUsed(payment);
                    totals[4] += amount;
                }
            }
            expected.put(payTo.toLowerCase(Locale.ROOT), totals);
        }

        Map<String, BigInteger[]> trial = trialBalanceByAccount();

        expected.forEach((payTo, t) -> {
            RevenueReport.Seller s = revenueOf(payTo);
            String desc = "seed " + seed + ", seller " + payTo;
            assertThat(s.grossSales().atomicUnits()).as(desc).isEqualTo(t[0]);
            assertThat(s.creditNotes().atomicUnits()).as(desc).isEqualTo(t[1]);
            assertThat(s.netRevenue().atomicUnits()).as(desc).isEqualTo(t[0] - t[1]);
            assertThat(s.customerCredits().atomicUnits()).as(desc).isEqualTo(t[1]);
            assertThat(s.sales()).as(desc).isEqualTo(t[2]);
            assertThat(s.credited()).as(desc).isEqualTo(t[3]);
            assertThat(s.chainVerified().grossSales().atomicUnits()).as(desc).isEqualTo(t[4]);
            assertThat(s.chainVerified().creditNotes().atomicUnits()).as(desc).isEqualTo(t[5]);
            assertThat(s.chainVerified().netRevenue().atomicUnits()).as(desc).isEqualTo(t[4] - t[5]);
            assertThat(s.unverifiedGrossSales().atomicUnits()).as(desc).isEqualTo(t[0] - t[4]);
            assertThat(s.chainVerified().grossSales().atomicUnits())
                    .as(desc)
                    .isLessThanOrEqualTo(s.grossSales().atomicUnits());
            assertThat(s.chainVerified().creditNotes().atomicUnits())
                    .as(desc)
                    .isLessThanOrEqualTo(s.creditNotes().atomicUnits());
            assertThat(s.openFindings()).as(desc).isZero();
            assertThat(s.saturated()).as(desc).isFalse();
            String prefix = "seller:" + payTo + ":";
            assertThat(BigInteger.valueOf(s.grossSales().atomicUnits()))
                    .as(desc)
                    .isEqualTo(trial.get(prefix + "revenue:data")[1]);
            BigInteger[] creditNotes = trial.getOrDefault(prefix + "revenue:credit-notes", zeros());
            assertThat(BigInteger.valueOf(s.creditNotes().atomicUnits()))
                    .as(desc)
                    .isEqualTo(creditNotes[0]);
            assertThat(BigInteger.valueOf(s.customerCredits().atomicUnits()))
                    .as(desc)
                    .isEqualTo(trial.getOrDefault(prefix + "liability:customer-credits", zeros())[1]);
        });
    }

    @Test
    void reconciliationHistoryIsNewestFirstWithoutItems() {
        Instant base = Instant.parse("2001-01-01T00:00:00Z").plusSeconds(random.nextInt(1_000_000));
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < ids.size(); i++) {
            jdbc.sql("""
                            INSERT INTO reconciliation_run (id, started_at, finished_at, status, network, safe_block,
                                                            checked, matched, pending, mismatches)
                            VALUES (:id, :startedAt, :finishedAt, 'COMPLETED', 'eip155:84532', :block, :checked,
                                    :checked, 0, 0)
                            """)
                    .param("id", ids.get(i))
                    .param("startedAt", Timestamp.from(base.plusSeconds(i)))
                    .param("finishedAt", Timestamp.from(base.plusSeconds(i).plusMillis(500)))
                    .param("block", 1_000L + i)
                    .param("checked", i + 1)
                    .update();
        }

        ReconciliationRunList history = get(RUNS + "?limit=100", ReconciliationRunList.class);
        List<ReconciliationRunList.Run> mine =
                history.items().stream().filter(r -> ids.contains(r.runId())).toList();

        assertThat(mine)
                .extracting(ReconciliationRunList.Run::runId)
                .containsExactly(ids.get(2), ids.get(1), ids.get(0));
        assertThat(mine.getFirst().summary().checked()).isEqualTo(3);
        assertThat(mine.getFirst().safeBlock()).isEqualTo(1_002L);
        assertThat(mine.getFirst().status()).isEqualTo("COMPLETED");
        assertThat(history.items())
                .isSortedAccordingTo(Comparator.comparing(ReconciliationRunList.Run::startedAt)
                        .reversed());
        assertThat(get(RUNS + "?limit=1", ReconciliationRunList.class).items()).hasSize(1);
        Map<String, Object> raw = getMap(RUNS + "?limit=1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw.get("items");
        assertThat(items.getFirst())
                .containsOnlyKeys("runId", "status", "startedAt", "finishedAt", "safeBlock", "summary");
    }

    /**
     * Marker test: a payment whose nonce is recognisable must not show its nonce or payment key (which embeds the
     * nonce) in any dashboard response, in either case. The payer address is allowed: it is public on Base Sepolia
     * and the BUYER book's account codes ({@code buyer:<payer>:wallet:available}) embed it, so the drill-down shows
     * it. (The orchestrator's own marker test bans "payer" in its bodies; that rule is the orchestrator's, unchanged.)
     */
    @Test
    void noResponseCarriesTheNonceOrThePaymentKey() {
        UUID runId = UUID.randomUUID();
        TestPayment base = TestPayment.random(random, 31_337);
        String nonce = "0x" + "c0ffee".repeat(10) + "beef";
        var authorization = new io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef(
                base.authorization().network(),
                base.authorization().asset(),
                base.authorization().payer(),
                nonce,
                base.authorization().validBefore());
        TestPayment marked = new TestPayment(authorization, base.amount(), base.payTo(), base.intentId(), runId);
        credit(marked);
        UUID paymentId = onlyPayment(runId).paymentId();
        jdbc.sql("""
                        INSERT INTO reconciliation_mismatch (id, payment_id, kind)
                        VALUES (:id, :paymentId, 'CONFLICTING_FACT')
                        """)
                .param("id", UUID.randomUUID())
                .param("paymentId", paymentId)
                .update();

        List<String> aboutThePayment = List.of(
                getString(PAYMENTS + "?runId=" + runId),
                getString(PAYMENTS + "?limit=100"),
                getString(PAYMENTS + "/" + paymentId));
        String revenue = getString(REVENUE + "?payTo=" + marked.payTo());
        assertThat(aboutThePayment).allSatisfy(body -> assertThat(body).contains(paymentId.toString()));
        assertThat(revenue).contains(marked.payTo().toLowerCase(Locale.ROOT));
        // Allowed on purpose: the payer address is public on chain.
        assertThat(aboutThePayment.get(2))
                .contains(marked.authorization().payer().toLowerCase(Locale.ROOT));

        String nonceHex = nonce.substring(2);
        List<String> bodies = new ArrayList<>(aboutThePayment);
        bodies.add(revenue);
        bodies.add(getString(RUNS + "?limit=100"));
        for (String body : bodies) {
            String lower = body.toLowerCase(Locale.ROOT);
            assertThat(lower).doesNotContain(nonceHex);
            assertThat(lower).doesNotContain(marked.key().toLowerCase(Locale.ROOT));
            assertThat(body).doesNotContain("paymentKey").doesNotContain("nonce");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {PAYMENTS, PAYMENTS + "/00000000-0000-0000-0000-000000000001", REVENUE, RUNS})
    void theHostGuardStillApplies(String path) throws IOException {
        assertThat(status(path, "evil.example")).isEqualTo(400);
        assertThat(status(path, "localhost.evil.example")).isEqualTo(400);
        assertThat(status(path, "localhost:" + port)).isIn(200, 404);
        assertThat(status("HEAD", path, "evil.example")).isEqualTo(400);
        assertThat(status("OPTIONS", path, "evil.example")).isEqualTo(400);
    }

    @Test
    void readsAreObservedPerRouteTemplate() {
        getString(REVENUE);
        client.get()
                .uri(PAYMENTS + "/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound();

        assertThat(meters.find("http.server.requests")
                        .tag("uri", "/api/v1/ledger/revenue")
                        .timer())
                .isNotNull();
        assertThat(meters.find("http.server.requests")
                        .tag("uri", "/api/v1/ledger/payments/{paymentId}")
                        .tag("status", "404")
                        .timer())
                .isNotNull();
    }

    /** The OpenAPI document is a build-time contract (OpenApiContractTests), not a runtime endpoint. */
    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui/index.html", "/swagger-ui.html"})
    void openApiDocumentIsOffAtRuntime(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
    }

    /** Unknown routes get the fixed 404 too: no request path in {@code detail} or {@code instance}. */
    @Test
    void unknownRouteDoesNotEchoThePath() {
        String body = client.get()
                .uri("/api/v1/ledger/ZZMARK")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull().doesNotContain("ZZMARK");
    }

    // --- helpers ---

    private static TestPayment inRun(TestPayment p, UUID runId) {
        return new TestPayment(p.authorization(), p.amount(), p.payTo(), p.intentId(), runId);
    }

    private void settle(TestPayment p) {
        record(p.authorized(), PaymentTopics.AUTHORIZED);
        record(p.buyerSettled(), PaymentTopics.SETTLED);
        record(p.sellerSettled(), PaymentTopics.SETTLED);
    }

    /** The upfront flow (ADR-0021): settled, then the seller did not serve the request and issued a credit note. */
    private void credit(TestPayment p) {
        settle(p);
        record(p.creditNoted(), PaymentTopics.CREDIT_NOTE_ISSUED);
    }

    private void record(Object event, String topic) {
        PaymentFact fact = switch (event) {
            case io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized e -> PaymentFact.of(e);
            case io.github.orhanyarkin.saiman.shared.payments.PaymentSettled e -> PaymentFact.of(e);
            case io.github.orhanyarkin.saiman.shared.payments.PaymentFailed e -> PaymentFact.of(e);
            case io.github.orhanyarkin.saiman.shared.payments.CreditNoteIssued e -> PaymentFact.of(e);
            default -> throw new IllegalArgumentException(event.getClass().getName());
        };
        ledger.record(fact, topic);
    }

    private PaymentSummary onlyPayment(UUID runId) {
        return get(PAYMENTS + "?runId=" + runId, PaymentPage.class).items().getFirst();
    }

    /**
     * The one revenue row of this seller, via the {@code payTo} filter: the shared context holds far more than the
     * 100 sellers an unfiltered report lists.
     */
    private RevenueReport.Seller revenueOf(String payTo) {
        RevenueReport report = get(REVENUE + "?payTo=" + payTo, RevenueReport.class);
        assertThat(report.truncated()).isFalse();
        assertThat(report.items()).hasSize(1);
        return report.items().getFirst();
    }

    private UUID paymentId(TestPayment p) {
        return jdbc.sql("SELECT id FROM payment WHERE payment_key = :key")
                .param("key", p.key())
                .query(UUID.class)
                .single();
    }

    /** What a reconciliation run leaves behind for a payment it saw on chain (state only; no findings). */
    private void chainUsed(TestPayment p) {
        jdbc.sql("UPDATE payment SET chain_state = 'USED' WHERE payment_key = :key")
                .param("key", p.key())
                .update();
    }

    /** seller-api confirmed the credit note with the ledger's tx hash and amount (V5). */
    private void corroborated(TestPayment p) {
        jdbc.sql("""
                        INSERT INTO credit_note_corroboration (payment_id, tx_hash, amount_atomic, corroborated_at)
                        SELECT id, seller_tx_hash, amount_atomic, now() FROM payment WHERE payment_key = :key
                        """).param("key", p.key()).update();
    }

    private void finding(TestPayment p, String kind) {
        jdbc.sql("""
                        INSERT INTO reconciliation_mismatch (id, payment_id, kind)
                        SELECT :id, id, :kind FROM payment WHERE payment_key = :key
                        """)
                .param("id", UUID.randomUUID())
                .param("kind", kind)
                .param("key", p.key())
                .update();
    }

    /** Account code to {debit, credit} from the trial balance endpoint. */
    private Map<String, BigInteger[]> trialBalanceByAccount() {
        List<Map<String, Object>> rows = client.get()
                .uri("/api/v1/ledger/trial-balance")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .returnResult()
                .getResponseBody();
        assertThat(rows).isNotNull();
        Set<String> seen = new HashSet<>();
        return rows.stream()
                .filter(r -> "USDC".equals(r.get("asset")) && seen.add((String) r.get("account")))
                .collect(Collectors.toMap(r -> (String) r.get("account"), r -> new BigInteger[] {
                    new BigInteger(r.get("debit").toString()),
                    new BigInteger(r.get("credit").toString())
                }));
    }

    private static BigInteger[] zeros() {
        return new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO};
    }

    private <T> T get(String uri, Class<T> type) {
        T body = client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(type)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private Map<String, Object> getMap(String uri) {
        Map<String, Object> body = client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private String getString(String uri) {
        String body = client.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private int status(String path, String host) throws IOException {
        return status("GET", path, host);
    }

    /** Writes a request with the given Host over a socket (HTTP clients refuse a foreign Host) and returns the status. */
    private int status(String method, String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            assertThat(statusLine).as("status line").isNotNull().startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
