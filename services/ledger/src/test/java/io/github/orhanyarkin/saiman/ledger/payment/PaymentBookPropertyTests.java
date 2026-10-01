package io.github.orhanyarkin.saiman.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.LedgerBook;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.ledger.journal.UnbalancedEntryException;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
import io.github.orhanyarkin.saiman.shared.money.Money;
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
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pure properties P1, P2 and P3 of ADR-0019 over {@link PaymentBook}, 1000 tries each. */
class PaymentBookPropertyTests {

    private static final int TRIES = 1000;

    static Stream<Arguments> tries() {
        return Pbt.tries(TRIES);
    }

    /**
     * P1: any interleaving of events about many payments (shared wallets, duplicates, reorderings, contradictory
     * reports) posts only balanced entries, each one-per-payment kind at most once, and the trial balance of every
     * asset sums to zero.
     */
    @ParameterizedTest(name = "P1 try {0} seed {1}")
    @MethodSource("tries")
    void p1EveryEntryBalancesAndTheTrialBalanceSumsToZero(int tryIndex, long seed) {
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
            Stories.story(random).forEach(step -> stream.add(Stories.fact(payment, step)));
        }
        List<PaymentFact> delivered = Stories.permutedWithDuplicates(stream, stream.size(), random);

        Pbt.check(tryIndex, seed, () -> describe(delivered), () -> {
            Map<String, PaymentProjection> projections = new HashMap<>();
            List<JournalEntry> posted = new ArrayList<>();
            for (PaymentFact fact : delivered) {
                PaymentProjection current =
                        projections.computeIfAbsent(fact.paymentKey(), k -> PaymentProjection.initial(fact));
                PaymentBook.Outcome outcome = PaymentBook.apply(current, fact);
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
        });
    }

    /**
     * P2: any permutation-with-duplicates of one payment's events yields the same projection and the same balances
     * as the canonical order.
     */
    @ParameterizedTest(name = "P2 try {0} seed {1}")
    @MethodSource("tries")
    void p2OrderAndDuplicatesDoNotChangeTheOutcome(int tryIndex, long seed) {
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

    /** P3 (domain half): an entry with one posting perturbed by a non-zero delta is rejected. */
    @ParameterizedTest(name = "P3 try {0} seed {1}")
    @MethodSource("tries")
    void p3APerturbedPostingIsRejectedByTheDomain(int tryIndex, long seed) {
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
        List<Posting> perturbed = perturb(entry.postings(), random);

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

    /** One posting changed by a delta in {@code [-(amount-1), +10^6]} excluding 0, so it stays positive. */
    public static List<Posting> perturb(List<Posting> postings, SplittableRandom random) {
        int index = random.nextInt(postings.size());
        Posting original = postings.get(index);
        long amount = original.amount().atomicUnits();
        long delta;
        if (amount > 1 && random.nextBoolean()) {
            delta = -random.nextLong(1, amount);
        } else {
            delta = random.nextLong(1, 1_000_001);
        }
        Money changed = new Money(
                amount + delta, original.amount().asset(), original.amount().decimals());
        List<Posting> copy = new ArrayList<>(postings);
        copy.set(index, new Posting(original.account(), original.side(), changed));
        return copy;
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
