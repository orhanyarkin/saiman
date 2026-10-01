package io.github.orhanyarkin.saiman.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.LedgerBook;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.ledger.journal.UnbalancedEntryException;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pure properties P1, P2 and P3 of ADR-0019 over {@link PaymentBook}, Pbt.tries() tries each, plus P4: the pinned
 * outcome of contradictory seller reports in every delivery order (ADR-0021).
 */
class PaymentBookPropertyTests {

    private static final int TRIES = Pbt.tries();

    /**
     * P1: any interleaving of events about many payments (shared wallets, duplicates, reorderings, contradictory
     * reports, credit notes) posts only balanced entries, each one-per-payment kind at most once, and the trial
     * balance of every asset sums to zero. A conflicting fact is rejected whole, as the listener does (quarantine).
     * Every credited payment carries a credit note of its full amount and nets to zero seller revenue.
     */
    @Test
    void p1EveryEntryBalancesAndTheTrialBalanceSumsToZero() {
        Pbt.forAll(TRIES, this::p1EveryEntryBalancesAndTheTrialBalanceSumsToZeroTry);
    }

    private void p1EveryEntryBalancesAndTheTrialBalanceSumsToZeroTry(int tryIndex, long seed) {
        var random = new SplittableRandom(seed);
        int payments = Pbt.size(tryIndex, TRIES, 12);
        List<String> payers = List.of(TestPayment.address(random), TestPayment.address(random));
        List<String> payees = List.of(TestPayment.address(random), TestPayment.address(random));
        List<PaymentFact> stream = new ArrayList<>();
        for (int i = 0; i < payments; i++) {
            TestPayment payment = TestPayment.of(
                    random,
                    payers.get(random.nextInt(payers.size())),
                    payees.get(random.nextInt(payees.size())),
                    Stories.amount(random));
            Stories.anyStory(random).forEach(step -> stream.add(Stories.fact(payment, step)));
        }
        List<PaymentFact> delivered = Stories.permutedWithDuplicates(stream, stream.size(), random);

        Pbt.check(tryIndex, seed, () -> describe(delivered), () -> {
            Map<String, PaymentProjection> projections = new HashMap<>();
            List<JournalEntry> posted = new ArrayList<>();
            for (PaymentFact fact : delivered) {
                PaymentProjection current =
                        projections.computeIfAbsent(fact.paymentKey(), k -> PaymentProjection.initial(fact));
                PaymentBook.Outcome outcome;
                try {
                    outcome = PaymentBook.apply(current, fact);
                } catch (ConflictingFactException e) {
                    assertThat(fact).isInstanceOfAny(PaymentFact.CreditNoted.class, PaymentFact.Failed.class);
                    continue;
                }
                projections.put(fact.paymentKey(), outcome.next());
                posted.addAll(outcome.entries());
            }

            Set<String> oncePerPayment = new HashSet<>();
            Set<Object> ids = new HashSet<>();
            Map<String, Long> perAsset = new TreeMap<>();
            for (JournalEntry entry : posted) {
                assertThat(entry.postings()).hasSizeGreaterThanOrEqualTo(2);
                assertThat(net(entry).values())
                        .as("entry %s balances per asset", entry.kind())
                        .containsOnly(0L);
                assertThat(ids.add(entry.id())).as("entry ids are unique").isTrue();
                if (entry.kind().oncePerPayment()) {
                    assertThat(oncePerPayment.add(entry.paymentKey() + "/" + entry.book() + "/" + entry.kind()))
                            .as("%s posted once per payment", entry.kind())
                            .isTrue();
                }
                entry.postings().forEach(p -> perAsset.merge(asset(p), p.signedAtomic(), Math::addExact));
            }
            assertThat(perAsset.values()).as("trial balance per asset").allMatch(sum -> sum == 0L);
            projections
                    .values()
                    .forEach(p -> assertThat(postedKinds(posted, p.paymentKey()))
                            .as("entries match the final states of %s", p.paymentKey())
                            .isEqualTo(expectedKinds(p, posted)));
            projections.values().stream()
                    .filter(p -> p.sellerState() == SellerState.CREDITED)
                    .forEach(p -> {
                        Map<String, Long> net = netPerAccount(posted, p.paymentKey());
                        long amount = p.amount().atomicUnits();
                        String seller = "seller:" + p.payTo() + ":";
                        assertThat(net.get(seller + "revenue:credit-notes"))
                                .as("credit-notes debit of %s", p.paymentKey())
                                .isEqualTo(amount);
                        assertThat(net.get(seller + "liability:customer-credits"))
                                .as("customer-credits credit of %s", p.paymentKey())
                                .isEqualTo(-amount);
                        assertThat(net.get(seller + "revenue:data") + net.get(seller + "revenue:credit-notes"))
                                .as("net seller revenue of %s", p.paymentKey())
                                .isZero();
                    });
        });
    }

    /** Signed ({@code debit - credit}) net per account over one payment's entries. */
    private static Map<String, Long> netPerAccount(List<JournalEntry> posted, String paymentKey) {
        return PaymentBookTests.balances(
                posted.stream().filter(e -> paymentKey.equals(e.paymentKey())).toList());
    }

    /**
     * P2: any permutation-with-duplicates of one payment's events yields the same projection and the same balances
     * as the canonical order.
     */
    @Test
    void p2OrderAndDuplicatesDoNotChangeTheOutcome() {
        Pbt.forAll(TRIES, this::p2OrderAndDuplicatesDoNotChangeTheOutcomeTry);
    }

    private void p2OrderAndDuplicatesDoNotChangeTheOutcomeTry(int tryIndex, long seed) {
        var random = new SplittableRandom(seed);
        TestPayment payment = TestPayment.random(random, Stories.amount(random));
        List<PaymentFact> canonical = Stories.story(random).stream()
                .map(step -> Stories.fact(payment, step))
                .toList();
        List<PaymentFact> delivered = Stories.permutedWithDuplicates(canonical, Pbt.size(tryIndex, TRIES, 8), random);

        Pbt.check(
                tryIndex,
                seed,
                () -> "canonical:\n" + describe(canonical) + "delivered:\n" + describe(delivered),
                () -> {
                    Replay expected = replay(canonical);
                    Replay actual = replay(delivered);
                    assertThat(actual.projection()).isEqualTo(expected.projection());
                    assertThat(actual.balances()).isEqualTo(expected.balances());
                });
    }

    /**
     * The seller reports that contradict each other (ADR-0021): a settle failure (AMBIGUOUS), a settled report and a
     * credit note about one payment, in every delivery order, each fact possibly redelivered. Each order either ends
     * in the canonical order's final state and balances or quarantines exactly one fact; which one, and the final
     * state, are pinned per order (by first delivery) so that changing them is a deliberate decision.
     *
     * <p>Today's behaviour, F = failed, S = settled, C = credit note, canonical F, S, C:
     *
     * <ul>
     *   <li>F,S,C and S,F,C: CREDITED (SALE + CREDIT_NOTE), nothing quarantined.
     *   <li>S,C,F, C,S,F and C,F,S: CREDITED, the late settle failure quarantined.
     *   <li>F,C,S: SETTLED (SALE only), the credit note quarantined: it arrived while the seller state was
     *       SETTLE_FAILED, and the settled report that later overrules the failure does not revive it.
     * </ul>
     *
     * Redeliveries (the same event again) do not always leave this as it is, which the second half checks: a settle
     * failure accepted before the credit note conflicts when redelivered after it, and a quarantined credit note
     * redelivered after the settled report is accepted (F,C,S then ends CREDITED). In production the inbox drops a
     * redelivery of an accepted event; a quarantined one goes to the DLT and is only re-applied by a replay.
     */
    @Test
    void p4ContradictorySellerReportsEndInAPinnedOutcomePerOrder() {
        Pbt.forAll(TRIES, this::p4ContradictorySellerReportsEndInAPinnedOutcomePerOrderTry);
    }

    private enum SellerReport {
        F,
        S,
        C
    }

    /** Every delivery order in a fixed sequence: Map.of iteration order varies per JVM, which would break seeds. */
    private static final List<List<SellerReport>> ORDERS = List.of(
            List.of(SellerReport.F, SellerReport.S, SellerReport.C),
            List.of(SellerReport.S, SellerReport.F, SellerReport.C),
            List.of(SellerReport.S, SellerReport.C, SellerReport.F),
            List.of(SellerReport.C, SellerReport.S, SellerReport.F),
            List.of(SellerReport.C, SellerReport.F, SellerReport.S),
            List.of(SellerReport.F, SellerReport.C, SellerReport.S));

    /** Expected final seller state and quarantined report, keyed by first-delivery order. */
    private static final Map<List<SellerReport>, Map.Entry<SellerState, Set<SellerReport>>> PINNED = Map.of(
            List.of(SellerReport.F, SellerReport.S, SellerReport.C), Map.entry(SellerState.CREDITED, Set.of()),
            List.of(SellerReport.S, SellerReport.F, SellerReport.C), Map.entry(SellerState.CREDITED, Set.of()),
            List.of(SellerReport.S, SellerReport.C, SellerReport.F),
                    Map.entry(SellerState.CREDITED, Set.of(SellerReport.F)),
            List.of(SellerReport.C, SellerReport.S, SellerReport.F),
                    Map.entry(SellerState.CREDITED, Set.of(SellerReport.F)),
            List.of(SellerReport.C, SellerReport.F, SellerReport.S),
                    Map.entry(SellerState.CREDITED, Set.of(SellerReport.F)),
            List.of(SellerReport.F, SellerReport.C, SellerReport.S),
                    Map.entry(SellerState.SETTLED, Set.of(SellerReport.C)));

    private void p4ContradictorySellerReportsEndInAPinnedOutcomePerOrderTry(int tryIndex, long seed) {
        var random = new SplittableRandom(seed);
        TestPayment payment = TestPayment.random(random, Stories.amount(random));
        // Built once: a redelivery is the same event (same id), not a new report.
        Map<SellerReport, PaymentFact> facts = Map.of(
                SellerReport.F, PaymentFact.of(payment.sellerSettleFailed()),
                SellerReport.S, PaymentFact.of(payment.sellerSettled()),
                SellerReport.C, PaymentFact.of(payment.creditNoted()));
        Map<String, SellerReport> byEventId = new HashMap<>();
        facts.forEach((report, fact) -> byEventId.put(fact.meta().eventId(), report));
        Replay canonical =
                replay(List.of(facts.get(SellerReport.F), facts.get(SellerReport.S), facts.get(SellerReport.C)));

        for (List<SellerReport> order : ORDERS) {
            List<PaymentFact> firstDeliveries = order.stream().map(facts::get).toList();
            List<PaymentFact> delivered = withRedeliveries(firstDeliveries, Pbt.size(tryIndex, TRIES, 4), random);
            SellerState expectedState = PINNED.get(order).getKey();
            Set<SellerReport> expectedQuarantined = PINNED.get(order).getValue();

            Pbt.check(tryIndex, seed, () -> "order " + order + ", delivered:\n" + describe(delivered), () -> {
                // Each report delivered once: exactly the pinned outcome.
                Delivery once = deliver(firstDeliveries, byEventId);
                assertThat(once.projection().sellerState())
                        .as("final seller state")
                        .isEqualTo(expectedState);
                assertThat(once.quarantined()).as("quarantined reports").isEqualTo(expectedQuarantined);
                assertThat(postedKinds(once.posted(), payment.key()))
                        .as("entries posted")
                        .isEqualTo(expectedKinds(once.projection(), once.posted()));
                assertThat(once.sameAs(canonical))
                        .as("ends like the canonical order")
                        .isEqualTo(expectedState == SellerState.CREDITED);

                // With redeliveries: a redelivery posts nothing new beyond what the final state implies, and the
                // outcome is still the canonical one or has exactly one report left quarantined. (A quarantined
                // credit note redelivered after the settled report is accepted: F,C,S then ends CREDITED.)
                Delivery redelivered = deliver(delivered, byEventId);
                assertThat(postedKinds(redelivered.posted(), payment.key()))
                        .as("entries posted with redeliveries")
                        .isEqualTo(expectedKinds(redelivered.projection(), redelivered.posted()));
                assertThat(redelivered.sameAs(canonical)
                                || redelivered.quarantined().size() == 1)
                        .as("same outcome as the canonical order, or exactly one report quarantined")
                        .isTrue();
                assertThat(redelivered.projection().sellerState())
                        .isIn(expectedState, canonical.projection().sellerState());
            });
        }
    }

    /**
     * The outcome of delivering {@code facts} in order, quarantining (skipping) a conflicting one as the listener
     * does. {@code quarantined} holds the reports whose last delivery was rejected.
     */
    private record Delivery(PaymentProjection projection, List<JournalEntry> posted, Set<SellerReport> quarantined) {
        boolean sameAs(Replay canonical) {
            return projection.equals(canonical.projection())
                    && PaymentBookTests.balances(posted).equals(canonical.balances());
        }
    }

    private static Delivery deliver(List<PaymentFact> facts, Map<String, SellerReport> byEventId) {
        PaymentProjection state = PaymentProjection.initial(facts.getFirst());
        List<JournalEntry> posted = new ArrayList<>();
        Set<SellerReport> quarantined = new HashSet<>();
        for (PaymentFact fact : facts) {
            SellerReport report = byEventId.get(fact.meta().eventId());
            try {
                PaymentBook.Outcome outcome = PaymentBook.apply(state, fact);
                state = outcome.next();
                posted.addAll(outcome.entries());
                quarantined.remove(report);
            } catch (ConflictingFactException e) {
                quarantined.add(report);
            }
        }
        return new Delivery(state, posted, quarantined);
    }

    /** {@code facts} in this order, with up to {@code max} redeliveries inserted after the original. */
    private static List<PaymentFact> withRedeliveries(List<PaymentFact> facts, int max, SplittableRandom random) {
        List<PaymentFact> delivered = new ArrayList<>(facts);
        int redeliveries = random.nextInt(max + 1);
        for (int i = 0; i < redeliveries; i++) {
            PaymentFact fact = facts.get(random.nextInt(facts.size()));
            int first = delivered.indexOf(fact);
            delivered.add(random.nextInt(first + 1, delivered.size() + 1), fact);
        }
        return delivered;
    }

    /** P3 (domain half): an entry with one posting perturbed by a non-zero delta is rejected. */
    @Test
    void p3APerturbedPostingIsRejectedByTheDomain() {
        Pbt.forAll(TRIES, this::p3APerturbedPostingIsRejectedByTheDomainTry);
    }

    private void p3APerturbedPostingIsRejectedByTheDomainTry(int tryIndex, long seed) {
        var random = new SplittableRandom(seed);
        TestPayment payment = TestPayment.random(random, Stories.amount(random));
        List<JournalEntry> entries = replay(Stories.story(random).stream()
                        .map(step -> Stories.fact(payment, step))
                        .toList())
                .entries();
        if (entries.isEmpty()) {
            return; // a story of seller failures only posts nothing; nothing to perturb
        }
        JournalEntry entry = entries.get(random.nextInt(entries.size()));
        List<Posting> perturbed = Stories.perturb(entry.postings(), random);

        Pbt.check(
                tryIndex,
                seed,
                () -> entry.kind() + " " + entry.postings() + "\nperturbed: " + perturbed,
                () -> assertThatThrownBy(() -> new JournalEntry(
                                entry.id(),
                                entry.paymentId(),
                                entry.paymentKey(),
                                entry.book(),
                                entry.kind(),
                                entry.sourceEventId(),
                                entry.reversesEntryId(),
                                entry.description(),
                                entry.effectiveAt(),
                                perturbed))
                        .isInstanceOf(UnbalancedEntryException.class));
    }

    record Replay(PaymentProjection projection, Map<String, Long> balances, List<JournalEntry> entries) {}

    static Replay replay(List<PaymentFact> facts) {
        PaymentProjection state = PaymentProjection.initial(facts.getFirst());
        List<JournalEntry> posted = new ArrayList<>();
        for (PaymentFact fact : facts) {
            PaymentBook.Outcome outcome = PaymentBook.apply(state, fact);
            state = outcome.next();
            posted.addAll(outcome.entries());
        }
        return new Replay(state, PaymentBookTests.balances(posted), posted);
    }

    private static Map<String, Long> net(JournalEntry entry) {
        Map<String, Long> net = new LinkedHashMap<>();
        entry.postings().forEach(p -> net.merge(asset(p), p.signedAtomic(), Math::addExact));
        return net;
    }

    private static String asset(Posting p) {
        return p.amount().asset() + "/" + p.amount().decimals();
    }

    private static Set<String> postedKinds(List<JournalEntry> posted, String key) {
        return posted.stream()
                .filter(e -> key.equals(e.paymentKey()))
                .map(e -> e.book() + ":" + e.kind())
                .collect(Collectors.toSet());
    }

    /** The kinds the final states imply, plus RELEASE + REVERSAL when a release was later overruled. */
    private static Set<String> expectedKinds(PaymentProjection p, List<JournalEntry> posted) {
        Set<String> kinds = new HashSet<>();
        p.buyerState().impliedEntries().forEach(k -> kinds.add(LedgerBook.BUYER + ":" + k));
        p.sellerState().impliedEntries().forEach(k -> kinds.add(LedgerBook.SELLER + ":" + k));
        boolean releaseOverruled =
                posted.stream().anyMatch(e -> p.paymentKey().equals(e.paymentKey()) && e.kind() == EntryKind.RELEASE);
        if (releaseOverruled && p.buyerState() == BuyerState.SETTLED) {
            kinds.add(LedgerBook.BUYER + ":" + EntryKind.RELEASE);
            kinds.add(LedgerBook.BUYER + ":" + EntryKind.REVERSAL);
        }
        return kinds;
    }

    private static String describe(List<PaymentFact> facts) {
        var sb = new StringBuilder();
        for (PaymentFact fact : facts) {
            sb.append("  ")
                    .append(fact.getClass().getSimpleName())
                    .append(fact instanceof PaymentFact.Settled s ? " " + s.book() : "")
                    .append(fact instanceof PaymentFact.CreditNoted c ? " tx=" + c.txHash() : "")
                    .append(
                            fact instanceof PaymentFact.Failed f
                                    ? " " + f.book() + " " + f.event().finality()
                                    : "")
                    .append(" key=...")
                    .append(fact.paymentKey().substring(fact.paymentKey().length() - 8))
                    .append(" amount=")
                    .append(fact.amount().atomicUnits())
                    .append(" event=")
                    .append(fact.meta().eventId())
                    .append('\n');
        }
        return sb.toString();
    }
}
