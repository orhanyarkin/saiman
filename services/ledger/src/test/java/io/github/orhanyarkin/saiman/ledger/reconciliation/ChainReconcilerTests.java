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
import io.github.orhanyarkin.saiman.evmrpc.UsdcAuthorizationUse;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /** One relayer transaction carrying two authorizations of the same payer: use, transfer, use, transfer. */
    private UsdcReceipt batch(String tx, TestPayment first, TestPayment second, boolean indexed) {
        String payer = first.authorization().payer();
        long unknown = UsdcTransfer.UNKNOWN_LOG_INDEX;
        return new UsdcReceipt(
                tx,
                999_000,
                true,
                List.of(
                        new UsdcTransfer(payer, first.payTo(), 20_000, indexed ? 11 : unknown),
                        new UsdcTransfer(payer, second.payTo(), 30_000, indexed ? 13 : unknown)),
                List.of(key(first), key(second)),
                List.of(
                        new UsdcAuthorizationUse(payer.toLowerCase(Locale.ROOT), nonce(first), indexed ? 10 : unknown),
                        new UsdcAuthorizationUse(
                                payer.toLowerCase(Locale.ROOT), nonce(second), indexed ? 12 : unknown)));
    }

    private static String nonce(TestPayment t) {
        return t.authorization().nonce().toLowerCase(Locale.ROOT);
    }

    private static String key(TestPayment t) {
        return t.authorization().payer().toLowerCase(Locale.ROOT) + ":" + nonce(t);
    }

    @Test
    void twoAuthorizationsRelayedInOneTransactionAreEachMatchedByLogOrder() {
        TestPayment second = TestPayment.of(random, payment.authorization().payer(), payment.payTo(), 30_000);
        String tx = payment.txHash();
        for (boolean indexed : List.of(true, false)) {
            UsdcReceipt receipt = batch(tx, payment, second, indexed);
            for (TestPayment t : List.of(payment, second)) {
                Books books = Books.of(List.of(PaymentFact.of(t.buyerSettled()), PaymentFact.of(t.sellerSettled())));
                PaymentProjection p = withTx(books.projection(), tx, null);

                var outcome = ChainReconciler.reconcile(
                        p,
                        books.nets(),
                        evidence(SAFE_LATE, Map.of(tx, Optional.of(receipt)), null),
                        SETTINGS,
                        UUID.randomUUID(),
                        NOW);

                assertThat(outcome.status())
                        .as("indexed=%s amount=%s", indexed, t.amount())
                        .isEqualTo(ItemStatus.MATCHED);
                assertThat(outcome.adjustment()).isNull();
            }
        }
    }

    @Test
    void anAuthorizationWithoutItsOwnTransferIsAPartyMismatchEvenIfTheBatchHasOne() {
        TestPayment second = TestPayment.of(random, payment.authorization().payer(), payment.payTo(), 30_000);
        String tx = payment.txHash();
        String payer = payment.authorization().payer();
        // use(first) at 10 is followed directly by use(second) at 11; the only transfer (12) belongs to second.
        UsdcReceipt receipt = new UsdcReceipt(
                tx,
                999_000,
                true,
                List.of(new UsdcTransfer(payer, payment.payTo(), 20_000, 12)),
                List.of(key(payment), key(second)),
                List.of(
                        new UsdcAuthorizationUse(payer.toLowerCase(Locale.ROOT), nonce(payment), 10),
                        new UsdcAuthorizationUse(payer.toLowerCase(Locale.ROOT), nonce(second), 11)));
        Books books = settledBothBooks();

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.of(receipt)), null));

        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.PARTY_MISMATCH);
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

    /**
     * ADR-0021 edge: the seller credited a payment the chain says was never used. The SALE's wallet movement goes
     * to suspense as SETTLED_BUT_UNUSED; the credit note touches no wallet, so its liability is left for a human
     * REVERSAL.
     */
    @Test
    void creditedButUnusedMovesOnlyTheSellerWalletToSuspense() {
        Books books = Books.of(List.of(PaymentFact.of(payment.creditNoted())));
        PaymentProjection p = books.projection();
        String tx = payment.txHash();

        var outcome = reconcile(books, evidence(SAFE_LATE, Map.of(tx, Optional.empty()), false));

        assertThat(outcome.status()).isEqualTo(ItemStatus.MISMATCH);
        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind, ChainReconciler.Finding::adjusted)
                .contains(tuple(MismatchKind.SETTLED_BUT_UNUSED, true));
        assertThat(legs(outcome))
                .containsExactlyInAnyOrder(
                        "DEBIT platform:suspense:usdc 20000", "CREDIT seller:" + p.payTo() + ":wallet 20000");
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

    /** The projection with these reported hashes (buyer, seller) and no chain facts. */
    private static PaymentProjection withTx(PaymentProjection p, @Nullable String buyerTx, @Nullable String sellerTx) {
        return new PaymentProjection(
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
                buyerTx,
                sellerTx,
                null,
                null);
    }

    @Test
    void bogusSellerHashDoesNotFreezeThePaymentTheValidReceiptWins() {
        Books books = settledBothBooks();
        String real = payment.txHash();
        PaymentProjection p = withTx(books.projection(), real, txHash('c'));
        Map<String, Optional<UsdcReceipt>> receipts =
                Map.of(real, Optional.of(matching(p, real)), txHash('c'), Optional.empty());

        assertThat(ChainReconciler.needsAuthorizationState(p, receipts, SAFE_LATE))
                .isFalse();
        var outcome = ChainReconciler.reconcile(
                p, books.nets(), evidence(SAFE_LATE, receipts, null), SETTINGS, UUID.randomUUID(), NOW);

        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind, ChainReconciler.Finding::reportedTxHash)
                .containsExactly(tuple(MismatchKind.TX_NOT_FOUND, txHash('c')));
        assertThat(outcome.next().chainState()).isEqualTo(ChainState.USED);
        assertThat(outcome.next().chainTxHash()).isEqualTo(real);
        assertThat(outcome.adjustment()).isNull();
    }

    @Test
    void bogusBuyerHashOfAnotherTransferIsNotForTheAuthorization() {
        Books books = settledBothBooks();
        String real = payment.txHash();
        String bogus = txHash('d');
        PaymentProjection p = withTx(books.projection(), bogus, real);
        var unrelated = new UsdcReceipt(
                bogus, 999_000, true, List.of(new UsdcTransfer(p.payer(), p.payTo(), 20_000)), List.of());
        Map<String, Optional<UsdcReceipt>> receipts =
                Map.of(real, Optional.of(matching(p, real)), bogus, Optional.of(unrelated));

        var outcome = ChainReconciler.reconcile(
                p, books.nets(), evidence(SAFE_LATE, receipts, null), SETTINGS, UUID.randomUUID(), NOW);

        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.TX_NOT_FOR_AUTHORIZATION);
        assertThat(outcome.next().chainTxHash()).isEqualTo(real);
        assertThat(outcome.adjustment()).isNull();
    }

    @Test
    void twoDifferentValidReceiptsAreAConflictWithoutPosting() {
        Books books = settledBothBooks();
        String real = payment.txHash();
        PaymentProjection p = withTx(books.projection(), real, txHash('c'));
        Map<String, Optional<UsdcReceipt>> receipts =
                Map.of(real, Optional.of(matching(p, real)), txHash('c'), Optional.of(matching(p, txHash('c'))));

        var outcome = ChainReconciler.reconcile(
                p, books.nets(), evidence(SAFE_LATE, receipts, null), SETTINGS, UUID.randomUUID(), NOW);

        assertThat(outcome.status()).isEqualTo(ItemStatus.MISMATCH);
        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.CONFLICTING_TX);
        assertThat(outcome.adjustment()).isNull();
        assertThat(outcome.next().chainState()).isEqualTo(p.chainState());
    }

    @Test
    void twoUnknownHashesWithinTheGraceArePending() {
        Books books = settledBothBooks();
        PaymentProjection p = withTx(books.projection(), payment.txHash(), txHash('c'));
        Map<String, Optional<UsdcReceipt>> receipts =
                Map.of(payment.txHash(), Optional.empty(), txHash('c'), Optional.empty());

        var outcome = ChainReconciler.reconcile(
                p, books.nets(), evidence(SAFE_EARLY, receipts, null), SETTINGS, UUID.randomUUID(), NOW);

        assertThat(outcome.status()).isEqualTo(ItemStatus.PENDING);
        assertThat(outcome.findings()).isEmpty();
    }

    @Test
    void usedAuthorizationWithoutAKnownTransactionIsTxUnknown() {
        Books books = Books.of(List.of(PaymentFact.of(payment.buyerSettled())));
        PaymentProjection noTx = withTx(books.projection(), null, null);

        var outcome = ChainReconciler.reconcile(
                noTx, books.nets(), evidence(SAFE_LATE, Map.of(), true), SETTINGS, UUID.randomUUID(), NOW);

        assertThat(outcome.status()).isEqualTo(ItemStatus.TX_UNKNOWN);
        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.next().chainState()).isEqualTo(ChainState.USED);
    }

    @Test
    void finalChainWithNeitherBookTerminalIsBooksOpen() {
        Books books = Books.of(List.of(PaymentFact.of(payment.authorized())));
        String tx = payment.txHash();

        var used = reconcile(books, evidence(SAFE_LATE, Map.of(), true));
        var usedWithTx = reconcile(books, evidence(SAFE_LATE, Map.of(), true, matching(books.projection(), tx)));
        var unused = reconcile(books, evidence(SAFE_LATE, Map.of(), false));

        for (var outcome : List.of(used, usedWithTx, unused)) {
            assertThat(outcome.status()).isEqualTo(ItemStatus.MISMATCH);
            assertThat(outcome.findings())
                    .extracting(ChainReconciler.Finding::kind)
                    .containsExactly(MismatchKind.BOOKS_OPEN);
            assertThat(outcome.adjustment()).isNull();
        }
        assertThat(unused.next().chainState()).isEqualTo(ChainState.UNUSED);
        assertThat(usedWithTx.next().chainTxHash()).isEqualTo(tx);
    }

    @Test
    void openBooksWithinTheGraceAfterValidBeforeAreNotYetBooksOpen() {
        Books books = Books.of(List.of(PaymentFact.of(payment.authorized())));
        ChainBlock withinGrace = new ChainBlock(SAFE_LATE.number(), VALID_BEFORE + 60);

        var outcome = reconcile(books, evidence(withinGrace, Map.of(), false));

        assertThat(outcome.status()).isEqualTo(ItemStatus.MATCHED);
        assertThat(outcome.findings()).isEmpty();
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

    /** A buyer that reported SETTLED without a tx hash: only the authorization state can decide it. */
    private Books settledWithoutTx() {
        Books books = Books.of(List.of(PaymentFact.of(payment.authorized()), PaymentFact.of(payment.buyerSettled())));
        return new Books(withTx(books.projection(), null, null), books.entries());
    }

    @Test
    void safeAtExactlyValidBeforeIsPendingEvenForAnUnusedAuthorization() {
        Books books = settledWithoutTx();
        ChainBlock atValidBefore = new ChainBlock(SAFE_LATE.number(), VALID_BEFORE);
        ChainBlock oneSecondLater = new ChainBlock(SAFE_LATE.number(), VALID_BEFORE + 1);

        assertThat(ChainReconciler.needsAuthorizationState(books.projection(), Map.of(), atValidBefore))
                .isFalse();
        var atBoundary = reconcile(books, evidence(atValidBefore, Map.of(), false));
        assertThat(atBoundary.status()).isEqualTo(ItemStatus.PENDING);
        assertThat(atBoundary.findings()).isEmpty();
        assertThat(atBoundary.adjustment()).isNull();

        assertThat(reconcile(books, evidence(oneSecondLater, Map.of(), false)).findings())
                .as("one second later the authorization is final")
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.SETTLED_BUT_UNUSED);
    }

    @Test
    void safeAtExactlyTheGraceAfterValidBeforeIsNotYetBooksOpen() {
        Books books = Books.of(List.of(PaymentFact.of(payment.authorized())));
        long grace = SETTINGS.graceAfterValidBefore().toSeconds();

        var atBoundary =
                reconcile(books, evidence(new ChainBlock(SAFE_LATE.number(), VALID_BEFORE + grace), Map.of(), false));
        assertThat(atBoundary.findings())
                .extracting(ChainReconciler.Finding::kind)
                .doesNotContain(MismatchKind.BOOKS_OPEN);

        var after = reconcile(
                books, evidence(new ChainBlock(SAFE_LATE.number(), VALID_BEFORE + grace + 1), Map.of(), false));
        assertThat(after.findings()).extracting(ChainReconciler.Finding::kind).containsExactly(MismatchKind.BOOKS_OPEN);
    }

    @Test
    void safeAtExactlyTheReceiptGraceIsNotYetTxNotFound() {
        Books books = settledBothBooks();
        Map<String, Optional<UsdcReceipt>> none = Map.of(payment.txHash(), Optional.empty());
        long receiptGrace = SETTINGS.receiptGrace().toSeconds();

        var atBoundary =
                reconcile(books, evidence(new ChainBlock(SAFE_LATE.number(), VALID_BEFORE + receiptGrace), none, true));
        assertThat(atBoundary.status()).isEqualTo(ItemStatus.PENDING);
        assertThat(atBoundary.findings()).isEmpty();

        var after = reconcile(
                books, evidence(new ChainBlock(SAFE_LATE.number(), VALID_BEFORE + receiptGrace + 1), none, true));
        assertThat(after.findings())
                .extracting(ChainReconciler.Finding::kind)
                .containsExactly(MismatchKind.TX_NOT_FOUND);
    }

    @Test
    void safeTimestampAheadOfTheLocalClockIsBoundedSoNothingExpiresEarly() {
        Books books = settledWithoutTx();
        ChainBlock reported = new ChainBlock(SAFE_LATE.number(), VALID_BEFORE + 30);
        long localNow = VALID_BEFORE - 5;

        ChainBlock effective = ReconciliationService.effective(reported, localNow);

        assertThat(effective.number()).as("the block number is never clamped").isEqualTo(reported.number());
        assertThat(effective.timestamp()).isEqualTo(localNow);
        assertThat(ChainReconciler.needsAuthorizationState(books.projection(), Map.of(), effective))
                .isFalse();
        var outcome = reconcile(books, evidence(effective, Map.of(), false));
        assertThat(outcome.status()).isEqualTo(ItemStatus.PENDING);
        assertThat(outcome.findings())
                .extracting(ChainReconciler.Finding::kind)
                .doesNotContain(MismatchKind.SETTLED_BUT_UNUSED);
        assertThat(outcome.adjustment()).isNull();

        assertThat(ReconciliationService.effective(reported, VALID_BEFORE + 31))
                .as("a safe block behind the local clock is used as is")
                .isEqualTo(reported);
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
