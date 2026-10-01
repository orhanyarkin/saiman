package io.github.orhanyarkin.saiman.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.JournalRepository;
import io.github.orhanyarkin.saiman.ledger.journal.TrialBalanceRow;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link PaymentLedgerService} against Postgres: inbox dedupe, projection locking and P2 with the database. */
@LedgerIntegrationTest
class PaymentLedgerServiceTests {

    private static final int DB_TRIES = 50;

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private JournalRepository journal;

    @Autowired
    private JdbcClient jdbc;

    static Stream<Arguments> dbTries() {
        return Pbt.tries(DB_TRIES);
    }

    /**
     * P2 (database-backed): payment A gets its events in canonical order, payment B (same wallets, amount and
     * story, another nonce) gets a permutation with redeliveries of the same event ids. Projections and balances
     * per account must be equal.
     */
    @ParameterizedTest(name = "P2 db try {0} seed {1}")
    @MethodSource("dbTries")
    void p2RedeliveryAndReorderingGiveTheCanonicalBooks(int tryIndex, long seed) {
        var random = new SplittableRandom(seed);
        TestPayment a = TestPayment.random(random, Stories.amount(random));
        TestPayment b = TestPayment.of(
                random, a.authorization().payer(), a.payTo(), a.amount().atomicUnits());
        b = new TestPayment(b.authorization(), b.amount(), b.payTo(), a.intentId(), a.runId());
        List<Stories.Step> story = Stories.story(random);
        TestPayment other = b;
        List<PaymentFact> canonical =
                story.stream().map(step -> Stories.fact(a, step)).toList();
        List<PaymentFact> delivered = Stories.permutedWithDuplicates(
                story.stream().map(step -> Stories.fact(other, step)).toList(), 4, random);

        Pbt.check(tryIndex, seed, () -> "story " + story + "\ndelivered " + describe(delivered), () -> {
            canonical.forEach(fact -> ledger.record(fact, topicOf(fact)));
            delivered.forEach(fact -> ledger.record(fact, topicOf(fact)));

            PaymentProjection expected = payments.findByKey(a.key()).orElseThrow();
            PaymentProjection actual = payments.findByKey(other.key()).orElseThrow();
            assertThat(actual)
                    .usingRecursiveComparison()
                    .ignoringFields("id", "paymentKey", "nonce", "buyerTxHash", "sellerTxHash")
                    .isEqualTo(expected);
            assertThat(actual.buyerTxHash() == null).isEqualTo(expected.buyerTxHash() == null);
            assertThat(balances(other.key())).isEqualTo(balances(a.key()));
            assertThat(oncePerPaymentKinds(other.key())).doesNotHaveDuplicates();
        });
    }

    @Test
    void redeliveredEventIsDroppedByTheInbox() {
        TestPayment payment = TestPayment.random(new SplittableRandom(), 20_000);
        PaymentFact settled = PaymentFact.of(payment.buyerSettled());

        List<JournalEntry> first = ledger.record(settled, PaymentTopics.SETTLED);
        List<JournalEntry> again = ledger.record(settled, PaymentTopics.SETTLED);

        assertThat(first).hasSize(2);
        assertThat(again).isEmpty();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM inbox WHERE event_id = :id AND consumer = 'ledger:payments.settled.v1'")
                        .param("id", settled.meta().eventId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void anEventIdSeenOnAnotherTopicDoesNotShadowTheEvent() {
        TestPayment payment = TestPayment.random(new SplittableRandom(), 20_000);
        PaymentAuthorized authorized = payment.authorized();
        PaymentSettled settled = payment.buyerSettled();
        PaymentSettled sameId = new PaymentSettled(
                authorized.meta(),
                settled.authorization(),
                settled.amount(),
                settled.payTo(),
                settled.resource(),
                settled.book(),
                settled.txHash(),
                settled.evidence(),
                settled.paymentIntentId(),
                settled.runId());

        assertThat(ledger.record(PaymentFact.of(authorized), PaymentTopics.AUTHORIZED))
                .hasSize(1);
        assertThat(ledger.record(PaymentFact.of(sameId), PaymentTopics.SETTLED))
                .extracting(JournalEntry::kind)
                .extracting(Enum::name)
                .containsExactly("SETTLE");
    }

    @Test
    void racingFirstEventsForOnePaymentPostEachEntryOnce() throws Exception {
        var random = new SplittableRandom();
        List<TestPayment> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(TestPayment.random(random, 10_000 + i));
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<JournalEntry>>> futures = new ArrayList<>();
            for (TestPayment payment : batch) {
                List<Callable<List<JournalEntry>>> racers = List.of(
                        () -> ledger.record(PaymentFact.of(payment.authorized()), PaymentTopics.AUTHORIZED),
                        () -> ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED),
                        () -> ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED));
                for (var racer : racers) {
                    futures.add(pool.submit(racer));
                }
            }
            for (var future : futures) {
                future.get();
            }
        }
        for (TestPayment payment : batch) {
            assertThat(oncePerPaymentKinds(payment.key())).containsExactlyInAnyOrder("ENCUMBER", "SETTLE", "SALE");
        }
    }

    @Test
    void trialBalanceSumsToZeroPerAsset() {
        TestPayment payment = TestPayment.random(new SplittableRandom(), 33_000);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);
        ledger.record(PaymentFact.of(payment.sellerSettled()), PaymentTopics.SETTLED);

        List<TrialBalanceRow> rows = journal.trialBalance();

        Map<String, BigInteger> perAsset = rows.stream()
                .collect(Collectors.groupingBy(
                        TrialBalanceRow::asset,
                        Collectors.reducing(BigInteger.ZERO, TrialBalanceRow::balance, BigInteger::add)));
        assertThat(perAsset.values()).containsOnly(BigInteger.ZERO);
        assertThat(rows)
                .allSatisfy(
                        row -> assertThat(row.balance()).isEqualTo(row.debit().subtract(row.credit())));
        String payer = payment.authorization().payer().toLowerCase(java.util.Locale.ROOT);
        assertThat(rows)
                .filteredOn(row -> row.account().equals("buyer:" + payer + ":expense:data"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.balance()).isEqualTo(BigInteger.valueOf(33_000));
                    assertThat(row.type()).isEqualTo("EXPENSE");
                    assertThat(row.book()).isEqualTo("BUYER");
                });
    }

    static String topicOf(PaymentFact fact) {
        return switch (fact) {
            case PaymentFact.Authorized a -> PaymentTopics.AUTHORIZED;
            case PaymentFact.Settled s -> PaymentTopics.SETTLED;
            case PaymentFact.Failed f -> PaymentTopics.FAILED;
        };
    }

    private Map<String, Long> balances(String paymentKey) {
        Map<String, Long> balances = new TreeMap<>();
        jdbc.sql("""
                        SELECT p.account_code,
                               sum(CASE p.side WHEN 'DEBIT' THEN p.amount_atomic ELSE -p.amount_atomic END) AS net
                          FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                         WHERE e.payment_key = :key
                         GROUP BY p.account_code
                        """)
                .param("key", paymentKey)
                .query((rs, row) -> balances.put(rs.getString(1), rs.getLong(2)))
                .list();
        return balances;
    }

    private List<String> oncePerPaymentKinds(String paymentKey) {
        return jdbc.sql("""
                        SELECT kind FROM journal_entry
                         WHERE payment_key = :key AND kind IN ('ENCUMBER', 'SETTLE', 'RELEASE', 'SALE')
                        """).param("key", paymentKey).query(String.class).list();
    }

    private static String describe(List<PaymentFact> facts) {
        return facts.stream()
                .map(f ->
                        f.getClass().getSimpleName() + "(" + f.meta().eventId().substring(0, 8) + ")")
                .collect(Collectors.joining(", "));
    }
}
