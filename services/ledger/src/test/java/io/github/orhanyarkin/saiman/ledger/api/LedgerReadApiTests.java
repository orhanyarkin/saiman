package io.github.orhanyarkin.saiman.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.query.PaymentDetail;
import io.github.orhanyarkin.saiman.ledger.query.PaymentPage;
import io.github.orhanyarkin.saiman.ledger.query.PaymentSummary;
import io.github.orhanyarkin.saiman.ledger.query.RevenueReport;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ReconciliationRunList;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Function;
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

        Map<String, RevenueReport.Seller> bySeller = revenueBySeller();

        assertThat(bySeller.get(payTo.toLowerCase(Locale.ROOT))).satisfies(s -> {
            assertThat(s.grossSales().atomicUnits()).isEqualTo(22_000);
            assertThat(s.creditNotes().atomicUnits()).isEqualTo(7_000);
            assertThat(s.netRevenue().atomicUnits()).isEqualTo(15_000);
            assertThat(s.customerCredits().atomicUnits()).isEqualTo(7_000);
            assertThat(s.sales()).isEqualTo(3);
            assertThat(s.credited()).isEqualTo(1);
            assertThat(s.grossSales().asset()).isEqualTo("USDC");
            assertThat(s.netRevenue().decimals()).isEqualTo(6);
        });
        assertThat(bySeller.get(creditedOnly.toLowerCase(Locale.ROOT))).satisfies(s -> {
            assertThat(s.grossSales().atomicUnits()).isEqualTo(9_000);
            assertThat(s.creditNotes().atomicUnits()).isEqualTo(9_000);
            assertThat(s.netRevenue().atomicUnits()).isZero();
            assertThat(s.customerCredits().atomicUnits()).isEqualTo(9_000);
            assertThat(s.sales()).isEqualTo(1);
            assertThat(s.credited()).isEqualTo(1);
        });
    }

    /**
     * Property-style (ADR-0019 approach: seeded random cases, seed in the failure message): for random sellers with
     * random mixes of settled and credited payments, every revenue figure equals both the sum of what was booked and
     * the trial balance's totals of the same accounts.
     */
    @Test
    void revenueEqualsTheTrialBalanceAndTheBookedAmounts() {
        long seed = random.nextLong();
        SplittableRandom cases = new SplittableRandom(seed);
        Map<String, long[]> expected = new java.util.HashMap<>(); // gross, creditNotes, sales, credited
        for (int s = 0; s < 4; s++) {
            String payTo = TestPayment.address(cases);
            long[] totals = new long[4];
            for (int p = 1 + cases.nextInt(4); p > 0; p--) {
                long amount = 1 + cases.nextLong(1_000_000_000L);
                TestPayment payment = TestPayment.of(cases, TestPayment.address(cases), payTo, amount);
                totals[0] += amount;
                totals[2]++;
                if (cases.nextBoolean()) {
                    credit(payment);
                    totals[1] += amount;
                    totals[3]++;
                } else {
                    settle(payment);
                }
            }
            expected.put(payTo.toLowerCase(Locale.ROOT), totals);
        }

        Map<String, RevenueReport.Seller> bySeller = revenueBySeller();
        Map<String, BigInteger[]> trial = trialBalanceByAccount();

        expected.forEach((payTo, t) -> {
            RevenueReport.Seller s = bySeller.get(payTo);
            String desc = "seed " + seed + ", seller " + payTo;
            assertThat(s).as(desc).isNotNull();
            assertThat(s.grossSales().atomicUnits()).as(desc).isEqualTo(t[0]);
            assertThat(s.creditNotes().atomicUnits()).as(desc).isEqualTo(t[1]);
            assertThat(s.netRevenue().atomicUnits()).as(desc).isEqualTo(t[0] - t[1]);
            assertThat(s.customerCredits().atomicUnits()).as(desc).isEqualTo(t[1]);
            assertThat(s.sales()).as(desc).isEqualTo(t[2]);
            assertThat(s.credited()).as(desc).isEqualTo(t[3]);
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
     * nonce) in any dashboard response, in either case.
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
        String revenue = getString(REVENUE);
        assertThat(aboutThePayment).allSatisfy(body -> assertThat(body).contains(paymentId.toString()));
        assertThat(revenue).contains(marked.payTo().toLowerCase(Locale.ROOT));

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
    @Test
    void openApiDocumentIsOffAtRuntime() {
        client.get().uri("/v3/api-docs").exchange().expectStatus().isNotFound();
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

    private Map<String, RevenueReport.Seller> revenueBySeller() {
        return get(REVENUE, RevenueReport.class).items().stream()
                .collect(Collectors.toMap(RevenueReport.Seller::payTo, Function.identity()));
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

    /** Writes a GET with the given Host over a socket (HTTP clients refuse a foreign Host) and returns the status. */
    private int status(String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
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
