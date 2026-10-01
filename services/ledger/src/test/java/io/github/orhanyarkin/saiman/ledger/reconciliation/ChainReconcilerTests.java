package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.SAFE_EARLY;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.SAFE_LATE;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.SETTINGS;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.VALID_BEFORE;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.evidence;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.matching;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.receipt;
import static io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.txHash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.evmrpc.UsdcTransfer;
import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.ledger.payment.ChainState;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.reconciliation.ChainFixtures.Books;
import io.github.orhanyarkin.saiman.shared.ledger.MismatchKind;
import io.github.orhanyarkin.saiman.shared.ledger.Side;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Worked examples of the chain step, one per outcome of ADR-0018 (the property tests cover the rest). */
class ChainReconcilerTests {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private final SplittableRandom random = new SplittableRandom(42);
    private final TestPayment payment = TestPayment.random(random, 20_000);

    private Books settledBothBooks() {
        return Books.of(List.of(
                PaymentFact.of(payment.authorized()),
                PaymentFact.of(payment.buyerSettled()),
                PaymentFact.of(payment.sellerSettled())));
    }

    private ChainReconciler.Outcome reconcile(Books books, ChainReconciler.Evidence evidence) {
        return ChainReconciler.reconcile(books.projection(), books.nets(), evidence, SETTINGS, UUID.randomUUID(), NOW);
    }

    @Test
    void settledPaymentWithMatchingReceiptIsMatched() {
        Books books = settledBothBooks();
        PaymentProjection p = books.projection();
        String tx = payment.txHash();

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(matching(p, tx))), null));

        assertThat(outcome.status()).isEqualTo(ItemStatus.MATCHED);
        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.adjustment()).isNull();
        assertThat(outcome.next().chainState()).isEqualTo(ChainState.USED);
        assertThat(outcome.next().chainTxHash()).isEqualTo(tx);
        assertThat(outcome.next().lastCheckedAt()).isEqualTo(NOW);
        assertThat(outcome.chainBlock()).isEqualTo(999_000L);
    }

    @Test
    void nonUsdcTwinWithAMatchingReceiptIsNeverMatched() {
        Books books = settledBothBooks();
        PaymentProjection real = books.projection();
        PaymentProjection twin = new PaymentProjection(
                real.id(),
                real.paymentKey(),
                real.network(),
                "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238",
                real.payer(),
                real.nonce(),
                real.payTo(),
                real.amount(),
                real.validBefore(),
                real.paymentIntentId(),
                real.runId(),
                real.buyerState(),
                real.sellerState(),
                real.chainState(),
                real.buyerTxHash(),
                real.sellerTxHash(),
                real.chainTxHash(),
                real.lastCheckedAt());
        String tx = payment.txHash();
        Map<String, Optional<UsdcReceipt>> receipts = Map.of(tx, Optional.of(matching(real, tx)));

        assertThat(ChainReconciler.needsAuthorizationState(twin, receipts, SAFE_LATE))
                .isFalse();
        var outcome = ChainReconciler.reconcile(
                twin,
                books.nets(),
                evidence(SAFE_LATE, receipts, true, matching(real, tx)),
                SETTINGS,
                UUID.randomUUID(),
                NOW);

        assertThat(outcome.status()).isEqualTo(ItemStatus.MISMATCH);
        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.TX_NOT_FOR_AUTHORIZATION);
        assertThat(outcome.adjustment()).isNull();
        assertThat(outcome.next().chainState()).isEqualTo(real.chainState());
    }

    @Test
    void buyerOnlyHistoryIsMatchedWithoutComparingTheUnreportedSellerBook() {
        Books books = Books.of(List.of(PaymentFact.of(payment.buyerSettled())));
        String tx = payment.txHash();

        var outcome =
                reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(matching(books.projection(), tx))), null));

        assertThat(outcome.status()).isEqualTo(ItemStatus.MATCHED);
        assertThat(outcome.adjustment()).isNull();
    }

    @Test
    void receiptAboveTheSafeBlockIsPending() {
        Books books = settledBothBooks();
        String tx = payment.txHash();
        UsdcReceipt fresh = receipt(books.projection(), tx, SAFE_LATE.number() + 1, payment.payTo(), 20_000);

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(fresh)), null));

        assertThat(outcome.status()).isEqualTo(ItemStatus.PENDING);
        assertThat(outcome.next().chainState()).isEqualTo(ChainState.UNKNOWN);
        assertThat(outcome.adjustment()).isNull();
    }

    @Test
    void failedReceiptWithAnUnusedAuthorizationMovesBothBooksToSuspense() {
        Books books = settledBothBooks();
        PaymentProjection p = books.projection();
        String tx = payment.txHash();
        var failed = new UsdcReceipt(tx, 999_000, false, List.of(), List.of());

        assertThat(ChainReconciler.needsAuthorizationState(p, Map.of(tx, Optional.of(failed)), SAFE_LATE))
                .isTrue();
        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(failed)), false));

        assertThat(outcome.status()).isEqualTo(ItemStatus.MISMATCH);
        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind, ChainReconciler.Finding::adjusted)
                .containsExactly(tuple(MismatchKind.SETTLED_BUT_UNUSED, true), tuple(MismatchKind.TX_FAILED, false));
        assertThat(outcome.next().chainState()).isEqualTo(ChainState.UNUSED);
        assertThat(legs(outcome))
                .containsExactlyInAnyOrder(
                        "DEBIT buyer:" + p.payer() + ":wallet:available 20000",
                        "CREDIT platform:suspense:usdc 20000",
                        "DEBIT platform:suspense:usdc 20000",
                        "CREDIT seller:" + p.payTo() + ":wallet 20000");
    }

    @Test
    void missingReceiptIsPendingWithinTheGraceAndNotFoundAfterIt() {
        Books books = settledBothBooks();
        String tx = payment.txHash();
        Map<String, Optional<UsdcReceipt>> none = Map.of(tx, Optional.empty());

        var early = reconcile(books, evidence(new ChainBlock(1_000_000, VALID_BEFORE + 300), none, null));
        assertThat(early.status()).isEqualTo(ItemStatus.PENDING);

        var late = reconcile(books, evidence(SAFE_LATE, none, true));
        assertThat(late.status()).isEqualTo(ItemStatus.MISMATCH);
        assertThat(late.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.TX_NOT_FOUND);
        assertThat(late.next().chainState()).isEqualTo(ChainState.USED);
        assertThat(late.adjustment()).isNull();
    }

    @Test
    void transferOfAnotherAmountIsAnAmountMismatchWithAnAdjustment() {
        Books books = settledBothBooks();
        PaymentProjection p = books.projection();
        String tx = payment.txHash();
        UsdcReceipt r = receipt(p, tx, 999_000, p.payTo(), 15_000);

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(r)), null));

        assertThat(outcome.findings()).hasSize(1);
        ChainReconciler.Finding finding = outcome.findings().getFirst();
        assertThat(finding.kind()).isEqualTo(MismatchKind.AMOUNT_MISMATCH);
        assertThat(finding.ledgerValue()).isEqualTo(Money.usdc(20_000));
        assertThat(finding.chainValue()).isEqualTo(Money.usdc(15_000));
        assertThat(finding.adjusted()).isTrue();
        assertThat(legs(outcome))
                .containsExactlyInAnyOrder(
                        "DEBIT buyer:" + p.payer() + ":wallet:available 5000",
                        "CREDIT platform:suspense:usdc 5000",
                        "DEBIT platform:suspense:usdc 5000",
                        "CREDIT seller:" + p.payTo() + ":wallet 5000");
    }

    @Test
    void transferToAnotherPartyIsAPartyMismatch() {
        Books books = settledBothBooks();
        PaymentProjection p = books.projection();
        String tx = payment.txHash();
        UsdcReceipt r = receipt(p, tx, 999_000, "0x" + "1".repeat(40), 20_000);

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(r)), null));

        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.PARTY_MISMATCH);
        // The buyer's money left as booked; only the seller's wallet never received it.
        assertThat(legs(outcome))
                .containsExactlyInAnyOrder(
                        "DEBIT platform:suspense:usdc 20000", "CREDIT seller:" + p.payTo() + ":wallet 20000");
    }

    @Test
    void receiptWithoutThePaymentsAuthorizationIsNotForTheAuthorization() {
        Books books = settledBothBooks();
        PaymentProjection p = books.projection();
        String tx = payment.txHash();
        var other =
                new UsdcReceipt(tx, 999_000, true, List.of(new UsdcTransfer(p.payer(), p.payTo(), 20_000)), List.of());
        String realTx = txHash('b');

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(other)), true, matching(p, realTx)));

        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.TX_NOT_FOR_AUTHORIZATION);
        assertThat(outcome.next().chainTxHash()).isEqualTo(realTx);
        assertThat(outcome.adjustment()).isNull();
    }

    @Test
    void buyerAndSellerReportingDifferentTransactionsIsAConflictWithoutPosting() {
        TestPayment seller = new TestPayment(
                payment.authorization(), payment.amount(), payment.payTo(), payment.intentId(), payment.runId());
        Books books = Books.of(List.of(PaymentFact.of(payment.buyerSettled()), PaymentFact.of(seller.sellerSettled())));
        PaymentProjection p = books.projection();
        PaymentProjection conflicting = new PaymentProjection(
                p.id(),
                p.paymentKey(),
                p.network(),
                p.assetAddress(),
                p.payer(),
                p.nonce(),
                p.payTo(),
                p.amount(),
                p.validBefore(),
                p.paymentIntentId(),
                p.runId(),
                p.buyerState(),
                p.sellerState(),
                p.chainState(),
                p.buyerTxHash(),
                txHash('c'),
                null,
                null);

        var outcome = ChainReconciler.reconcile(
                conflicting, books.nets(), evidence(SAFE_LATE, Map.of(), null), SETTINGS, UUID.randomUUID(), NOW);

        assertThat(outcome.status()).isEqualTo(ItemStatus.MISMATCH);
        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.CONFLICTING_TX);
        assertThat(outcome.adjustment()).isNull();
        assertThat(ChainReconciler.needsAuthorizationState(conflicting, Map.of(), SAFE_LATE))
                .isFalse();
    }

    @Test
    void usedAuthorizationWithoutAKnownTransactionIsTxUnknown() {
        Books books = Books.of(List.of(PaymentFact.of(payment.authorized())));

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(), true));

        assertThat(outcome.status()).isEqualTo(ItemStatus.TX_UNKNOWN);
        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.next().chainState()).isEqualTo(ChainState.USED);
    }

    @Test
    void unexpiredAuthorizationIsPending() {
        Books books = Books.of(List.of(PaymentFact.of(payment.authorized())));
        PaymentProjection p = books.projection();

        assertThat(ChainReconciler.needsAuthorizationState(p, Map.of(), SAFE_EARLY))
                .isFalse();
        assertThat(reconcile(books, evidence(SAFE_EARLY, Map.of(), null)).status())
                .isEqualTo(ItemStatus.PENDING);
    }

    @Test
    void settledReportWithoutTxButUnusedAuthorizationIsSettledButUnused() {
        // A buyer that settled through the resolver path without a tx hash, but the chain says unused.
        Books books = Books.of(List.of(PaymentFact.of(payment.authorized()), PaymentFact.of(payment.buyerSettled())));
        PaymentProjection p = books.projection();
        PaymentProjection noTx = new PaymentProjection(
                p.id(),
                p.paymentKey(),
                p.network(),
                p.assetAddress(),
                p.payer(),
                p.nonce(),
                p.payTo(),
                p.amount(),
                p.validBefore(),
                p.paymentIntentId(),
                p.runId(),
                p.buyerState(),
                p.sellerState(),
                p.chainState(),
                null,
                null,
                null,
                null);

        var outcome = ChainReconciler.reconcile(
                noTx, books.nets(), evidence(SAFE_LATE, Map.of(), false), SETTINGS, UUID.randomUUID(), NOW);

        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.SETTLED_BUT_UNUSED);
        assertThat(outcome.next().chainState()).isEqualTo(ChainState.UNUSED);
        assertThat(legs(outcome))
                .containsExactlyInAnyOrder(
                        "DEBIT buyer:" + p.payer() + ":wallet:available 20000", "CREDIT platform:suspense:usdc 20000");
    }

    @Test
    void releasedButUsedOnChainIsUnusedButSettled() {
        Books books =
                Books.of(List.of(PaymentFact.of(payment.authorized()), PaymentFact.of(payment.buyerExpiredUnused())));
        PaymentProjection p = books.projection();

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(), true, matching(p, txHash('d'))));

        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.UNUSED_BUT_SETTLED);
        assertThat(legs(outcome))
                .containsExactlyInAnyOrder(
                        "DEBIT platform:suspense:usdc 20000", "CREDIT buyer:" + p.payer() + ":wallet:available 20000");
    }

    @Test
    void terminalPaymentWithAnEncumbranceLeftIsReported() {
        Books books = settledBothBooks();
        String tx = payment.txHash();
        var nets = books.nets();
        var leftover = new ChainReconciler.LedgerNets(nets.buyerWallet(), 3_000, nets.sellerWallet());

        var outcome = ChainReconciler.reconcile(
                books.projection(),
                leftover,
                evidence(SAFE_LATE, Map.of(tx, Optional.of(matching(books.projection(), tx))), null),
                SETTINGS,
                UUID.randomUUID(),
                NOW);

        assertThat(outcome.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(MismatchKind.ENCUMBRANCE_NOT_CLEARED);
            assertThat(f.ledgerValue()).isEqualTo(Money.usdc(3_000));
            assertThat(f.adjusted()).isFalse();
        });
        assertThat(outcome.adjustment()).isNull();
    }

    @Test
    void tamperedSaleIsMovedToSuspenseOnceAndTheRerunPostsNothing() {
        Books books = settledBothBooks();
        PaymentProjection p = books.projection();
        String tx = payment.txHash();
        var tampered = new ChainReconciler.LedgerNets(
                books.nets().buyerWallet(), 0, books.nets().sellerWallet() + 5_000);
        var evidence = evidence(SAFE_LATE, Map.of(tx, Optional.of(matching(p, tx))), null);

        var first = ChainReconciler.reconcile(p, tampered, evidence, SETTINGS, UUID.randomUUID(), NOW);

        assertThat(first.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(MismatchKind.AMOUNT_MISMATCH);
            assertThat(f.ledgerValue()).isEqualTo(Money.usdc(25_000));
            assertThat(f.chainValue()).isEqualTo(Money.usdc(20_000));
        });
        assertThat(first.adjustment()).isNotNull();
        assertThat(first.adjustment().kind()).isEqualTo(EntryKind.ADJUSTMENT);
        assertThat(legs(first))
                .containsExactlyInAnyOrder(
                        "DEBIT platform:suspense:usdc 5000", "CREDIT seller:" + p.payTo() + ":wallet 5000");

        var afterAdjustment =
                new ChainReconciler.LedgerNets(tampered.buyerWallet(), 0, tampered.sellerWallet() - 5_000);
        var rerun =
                ChainReconciler.reconcile(first.next(), afterAdjustment, evidence, SETTINGS, UUID.randomUUID(), NOW);
        assertThat(rerun.status()).isEqualTo(ItemStatus.MATCHED);
        assertThat(rerun.adjustment()).isNull();
    }

    private static List<String> legs(ChainReconciler.Outcome outcome) {
        assertThat(outcome.adjustment()).isNotNull();
        return outcome.adjustment().postings().stream()
                .map(ChainReconcilerTests::leg)
                .toList();
    }

    private static String leg(Posting p) {
        return (p.side() == Side.DEBIT ? "DEBIT " : "CREDIT ") + p.account().code() + " "
                + p.amount().atomicUnits();
    }
}
