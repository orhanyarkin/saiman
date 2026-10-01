package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.SAFE_LATE;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.SETTINGS;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.receipt;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.txHash;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.ledger.journal.Account;
import io.github.orhanyarkin.saiman.ledger.journal.AccountType;
import io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts;
import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.LedgerBook;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.ledger.payment.BuyerState;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.SellerState;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.Books;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Properties of the chain step (ADR-0019 seeded generators): random books (any producer story), a random tamper
 * on the posted amounts, and a random chain (matching, other amount, other party, failed, unused, used without a
 * known tx).
 *
 * <ul>
 *   <li><b>P4 balance:</b> books plus adjustment still balance per asset (every entry does), and suspense absorbs
 *       exactly the corrections.
 *   <li><b>P5 convergence:</b> after the adjustment, every compared wallet's net equals the chain's movement.
 *   <li><b>P6 idempotence:</b> a rerun on the adjusted books posts nothing and reports the same chain state.
 * </ul>
 */
class ChainReconcilerPropertyTests {

    private static final int TRIES = 500;
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    static Stream<Arguments> tries() {
        return Pbt.tries(TRIES);
    }

    private enum Chain {
        MATCHING,
        OTHER_AMOUNT,
        OTHER_PARTY,
        FAILED_UNUSED,
        UNUSED,
        USED_NO_TX
    }

    /** The evidence plus the movements it implies for the two wallets (when a canonical receipt or UNUSED decides). */
    private record Scenario(ChainReconciler.Evidence evidence, long chainBuyer, long chainSeller) {}

    @ParameterizedTest(name = "P4-P6 try {0} seed {1}")
    @MethodSource("tries")
    void adjustmentsBalanceConvergeAndAreIdempotent(int tryIndex, long seed) {
        var random = new SplittableRandom(seed);
        TestPayment payment = TestPayment.random(random, 1 + random.nextLong(1_000_000));
        List<PaymentFact> facts = story(payment, random);
        Chain chain = Chain.values()[random.nextInt(Chain.values().length)];
        long tamper = random.nextInt(3) == 0 ? 1 + random.nextLong(50_000) : 0;

        Pbt.check(
                tryIndex,
                seed,
                () -> "facts "
                        + facts.stream().map(f -> f.getClass().getSimpleName()).toList() + "\nchain " + chain
                        + " tamper " + tamper,
                () -> {
                    Books books = Books.of(facts);
                    if (tamper > 0) {
                        books = books.plus(tamperEntry(books.projection(), tamper), books.projection());
                    }
                    PaymentProjection p = books.projection();
                    String tx = p.buyerTxHash() != null ? p.buyerTxHash() : p.sellerTxHash();
                    Scenario scenario = scenario(p, tx, chain, random);
                    var evidence = scenario.evidence();

                    var first = ChainReconciler.reconcile(p, books.nets(), evidence, SETTINGS, UUID.randomUUID(), NOW);
                    Books adjusted = books.plus(first.adjustment(), first.next());

                    // P4: every entry balances, so the whole book does; suspense holds exactly the corrections.
                    Map<String, Long> perAsset = new HashMap<>();
                    long suspense = 0;
                    for (JournalEntry entry : adjusted.entries()) {
                        for (Posting posting : entry.postings()) {
                            perAsset.merge(posting.amount().asset(), posting.signedAtomic(), Math::addExact);
                            if (posting.account().type() == AccountType.SUSPENSE) {
                                assertThat(entry.kind()).isEqualTo(EntryKind.ADJUSTMENT);
                                suspense += posting.signedAtomic();
                            }
                        }
                    }
                    assertThat(perAsset.values()).allMatch(v -> v == 0);
                    var before = books.nets();
                    var after = adjusted.nets();
                    assertThat(suspense)
                            .isEqualTo((before.buyerWallet() - after.buyerWallet())
                                    + (before.sellerWallet() - after.sellerWallet()));
                    if (first.adjustment() != null) {
                        assertThat(first.findings()).isNotEmpty();
                        assertThat(first.findings().getFirst().adjusted()).isTrue();
                        assertThat(first.status()).isEqualTo(ItemStatus.MISMATCH);
                    }

                    // P5: compared wallets now equal the chain (only when the chain decided the movement).
                    if (chainDecided(first)) {
                        long chainBuyer = scenario.chainBuyer();
                        long chainSeller = scenario.chainSeller();
                        if (p.buyerState() == BuyerState.SETTLED || p.buyerState() == BuyerState.RELEASED) {
                            assertThat(after.buyerWallet()).isEqualTo(chainBuyer);
                        }
                        if (p.sellerState() != SellerState.NONE) {
                            assertThat(after.sellerWallet()).isEqualTo(chainSeller);
                        }
                    }

                    // P6: rerun posts nothing and agrees on the chain state.
                    var rerun = ChainReconciler.reconcile(
                            adjusted.projection(), after, evidence, SETTINGS, UUID.randomUUID(), NOW);
                    assertThat(rerun.adjustment()).isNull();
                    assertThat(rerun.next().chainState()).isEqualTo(first.next().chainState());
                    assertThat(rerun.findings()).noneMatch(ChainReconciler.Finding::adjusted);
                });
    }

    /** The chain fixed the movement: authorization unused, or used with a canonical receipt. */
    private static boolean chainDecided(ChainReconciler.Outcome outcome) {
        return switch (outcome.next().chainState()) {
            case UNUSED -> true;
            case USED -> outcome.chainBlock() != null;
            case UNKNOWN -> false;
        };
    }

    /** A random producer story: buyer authorized/settled/released, seller settled/failed, in random order. */
    private static List<PaymentFact> story(TestPayment payment, SplittableRandom random) {
        List<PaymentFact> facts = new ArrayList<>();
        if (random.nextBoolean()) {
            facts.add(PaymentFact.of(payment.authorized()));
        }
        switch (random.nextInt(3)) {
            case 0 -> facts.add(PaymentFact.of(payment.buyerSettled()));
            case 1 -> facts.add(PaymentFact.of(payment.buyerExpiredUnused()));
            default -> {}
        }
        switch (random.nextInt(3)) {
            case 0 -> facts.add(PaymentFact.of(payment.sellerSettled()));
            case 1 -> facts.add(PaymentFact.of(payment.sellerSettleFailed()));
            default -> {}
        }
        if (facts.isEmpty()) {
            facts.add(PaymentFact.of(payment.authorized()));
        }
        for (int i = facts.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            facts.set(i, facts.set(j, facts.get(i)));
        }
        return facts;
    }

    /** The tamper demo in miniature: both postings of the seller's sale (or a buyer leg) raised by {@code delta}. */
    private static JournalEntry tamperEntry(PaymentProjection p, long delta) {
        Money amount = Money.usdc(delta);
        Account wallet = p.sellerState() == SellerState.SETTLED
                ? ChartOfAccounts.sellerWallet(p.payTo())
                : ChartOfAccounts.buyerAvailable(p.payer());
        Account other = p.sellerState() == SellerState.SETTLED
                ? ChartOfAccounts.sellerRevenue(p.payTo())
                : ChartOfAccounts.buyerExpense(p.payer());
        return new JournalEntry(
                UUID.randomUUID(),
                p.id(),
                p.paymentKey(),
                LedgerBook.SELLER,
                EntryKind.ADJUSTMENT,
                null,
                null,
                "tamper",
                NOW,
                List.of(Posting.debit(wallet, amount), Posting.credit(other, amount)));
    }

    private static Scenario scenario(PaymentProjection p, @Nullable String tx, Chain chain, SplittableRandom random) {
        String hash = tx != null ? tx : txHash('e');
        long amount = p.amount().atomicUnits();
        long paid = chain == Chain.OTHER_AMOUNT ? amount + 1 + random.nextInt(1000) : amount;
        UsdcReceipt receipt = switch (chain) {
            case OTHER_PARTY -> receipt(p, hash, 999_000, "0x" + "2".repeat(40), paid);
            case FAILED_UNUSED -> new UsdcReceipt(hash, 999_000, false, List.of(), List.of());
            default -> receipt(p, hash, 999_000, p.payTo(), paid);
        };
        Map<String, Optional<UsdcReceipt>> receipts = new HashMap<>();
        boolean used;
        UsdcReceipt found = null;
        long buyer = 0;
        long seller = 0;
        switch (chain) {
            case UNUSED, FAILED_UNUSED -> {
                if (tx != null) {
                    receipts.put(tx, chain == Chain.UNUSED ? Optional.empty() : Optional.of(receipt));
                }
                used = false;
            }
            case USED_NO_TX -> {
                if (tx != null) {
                    receipts.put(tx, Optional.empty());
                }
                used = true;
            }
            default -> {
                if (tx != null) {
                    receipts.put(tx, Optional.of(receipt));
                } else {
                    found = receipt;
                }
                used = true;
                buyer = -paid;
                seller = chain == Chain.OTHER_PARTY ? 0 : paid;
            }
        }
        return new Scenario(new ChainReconciler.Evidence(SAFE_LATE, receipts, used, found), buyer, seller);
    }
}
