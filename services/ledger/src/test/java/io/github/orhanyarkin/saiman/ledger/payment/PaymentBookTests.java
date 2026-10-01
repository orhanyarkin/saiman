package io.github.orhanyarkin.saiman.ledger.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.AuthorizationRef;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The worked examples of docs/design/m4-ledger.md and the order-independence corner cases, one by one. */
class PaymentBookTests {

    private final SplittableRandom random = new SplittableRandom(42);
    private final TestPayment payment = TestPayment.random(random, 20_000);
    private final String buyer = payment.authorization().payer().toLowerCase(Locale.ROOT);
    private final String seller = payment.payTo().toLowerCase(Locale.ROOT);

    @Test
    void settledCallPostsEncumberSaleAndSettleWithTheDesignAccounts() {
        List<JournalEntry> posted = run(
                PaymentFact.of(payment.authorized()),
                PaymentFact.of(payment.sellerSettled()),
                PaymentFact.of(payment.buyerSettled()));

        assertThat(posted)
                .extracting(JournalEntry::kind)
                .containsExactly(EntryKind.ENCUMBER, EntryKind.SALE, EntryKind.SETTLE);
        assertThat(lines(posted.get(0)))
                .containsExactly(
                        "DEBIT buyer:" + buyer + ":wallet:encumbered 20000",
                        "CREDIT buyer:" + buyer + ":wallet:available 20000");
        assertThat(lines(posted.get(1)))
                .containsExactly(
                        "DEBIT seller:" + seller + ":wallet 20000", "CREDIT seller:" + seller + ":revenue:data 20000");
        assertThat(lines(posted.get(2)))
                .containsExactly(
                        "DEBIT buyer:" + buyer + ":expense:data 20000",
                        "CREDIT buyer:" + buyer + ":wallet:encumbered 20000");
        assertThat(balances(posted))
                .containsEntry("buyer:" + buyer + ":wallet:encumbered", 0L)
                .containsEntry("buyer:" + buyer + ":wallet:available", -20_000L)
                .containsEntry("buyer:" + buyer + ":expense:data", 20_000L);
    }

    @Test
    void heldThenReleasedNetsToZero() {
        List<JournalEntry> posted =
                run(PaymentFact.of(payment.authorized()), PaymentFact.of(payment.buyerExpiredUnused()));

        assertThat(posted).extracting(JournalEntry::kind).containsExactly(EntryKind.ENCUMBER, EntryKind.RELEASE);
        assertThat(balances(posted).values()).containsOnly(0L);
    }

    @Test
    void settledBeforeAuthorizedPostsEncumberAndSettleAndTheLateAuthorizedPostsNothing() {
        PaymentFact settled = PaymentFact.of(payment.buyerSettled());
        PaymentBook.Outcome first = PaymentBook.apply(PaymentProjection.initial(settled), settled);
        assertThat(first.entries())
                .extracting(JournalEntry::kind)
                .containsExactly(EntryKind.ENCUMBER, EntryKind.SETTLE);

        PaymentBook.Outcome late = PaymentBook.apply(first.next(), PaymentFact.of(payment.authorized()));
        assertThat(late.entries()).isEmpty();
        assertThat(late.next().buyerState()).isEqualTo(BuyerState.SETTLED);
    }

    @Test
    void duplicateFactPostsNothing() {
        PaymentFact settled = PaymentFact.of(payment.sellerSettled());
        PaymentBook.Outcome first = PaymentBook.apply(PaymentProjection.initial(settled), settled);
        PaymentBook.Outcome again = PaymentBook.apply(first.next(), settled);

        assertThat(again.entries()).isEmpty();
        assertThat(again.next()).isEqualTo(first.next());
    }

    @Test
    void sellerFactsNeverTouchTheBuyerBook() {
        List<JournalEntry> posted =
                run(PaymentFact.of(payment.sellerSettleFailed()), PaymentFact.of(payment.sellerSettled()));

        assertThat(posted).extracting(JournalEntry::kind).containsExactly(EntryKind.SALE);
    }

    @Test
    void approvalRejectedMeansNoEventsAndNoEntries() {
        // An intent rejected before signing never produces a payments.* event: nothing to apply, nothing posted.
        assertThat(run()).isEmpty();
    }

    @Test
    void settledAfterReleaseReversesTheRelease() {
        List<JournalEntry> posted = run(
                PaymentFact.of(payment.authorized()),
                PaymentFact.of(payment.buyerExpiredUnused()),
                PaymentFact.of(payment.buyerSettled()));

        assertThat(posted)
                .extracting(JournalEntry::kind)
                .containsExactly(EntryKind.ENCUMBER, EntryKind.RELEASE, EntryKind.SETTLE, EntryKind.REVERSAL);
        JournalEntry reversal = posted.get(3);
        assertThat(reversal.reversesEntryId()).isEqualTo(posted.get(1).id());
        // Same balances as authorized + settled.
        assertThat(balances(posted))
                .isEqualTo(balances(run(PaymentFact.of(payment.authorized()), PaymentFact.of(payment.buyerSettled()))));
    }

    @Test
    void conflictingAmountIsRejected() {
        PaymentFact authorized = PaymentFact.of(payment.authorized());
        PaymentBook.Outcome first = PaymentBook.apply(PaymentProjection.initial(authorized), authorized);
        PaymentSettled other = payment.buyerSettled();
        PaymentSettled inflated = new PaymentSettled(
                other.meta(),
                other.authorization(),
                Money.usdc(25_000),
                other.payTo(),
                other.resource(),
                other.book(),
                other.txHash(),
                other.evidence(),
                other.paymentIntentId(),
                other.runId());

        assertThatThrownBy(() -> PaymentBook.apply(first.next(), PaymentFact.of(inflated)))
                .isInstanceOf(ConflictingFactException.class);
    }

    @Test
    void entryIdsAreStableAcrossReplays() {
        List<JournalEntry> once = run(PaymentFact.of(payment.authorized()), PaymentFact.of(payment.buyerSettled()));
        List<JournalEntry> twice = run(PaymentFact.of(payment.buyerSettled()), PaymentFact.of(payment.authorized()));

        assertThat(twice)
                .extracting(JournalEntry::id)
                .containsExactlyElementsOf(once.stream().map(JournalEntry::id).toList());
    }

    @Test
    void paymentKeyIsLowerCase() {
        AuthorizationRef auth = payment.authorization();
        PaymentProjection p = PaymentProjection.initial(PaymentFact.of(payment.authorized()));

        assertThat(p.paymentKey())
                .isEqualTo(p.paymentKey().toLowerCase(Locale.ROOT))
                .contains(buyer);
        assertThat(p.payer()).isEqualTo(auth.payer().toLowerCase(Locale.ROOT));
    }

    static List<JournalEntry> run(PaymentFact... facts) {
        List<JournalEntry> posted = new ArrayList<>();
        PaymentProjection state = null;
        for (PaymentFact fact : facts) {
            if (state == null) {
                state = PaymentProjection.initial(fact);
            }
            PaymentBook.Outcome outcome = PaymentBook.apply(state, fact);
            posted.addAll(outcome.entries());
            state = outcome.next();
        }
        return posted;
    }

    static Map<String, Long> balances(List<JournalEntry> entries) {
        Map<String, Long> balances = new TreeMap<>();
        for (JournalEntry entry : entries) {
            for (Posting posting : entry.postings()) {
                balances.merge(posting.account().code(), posting.signedAtomic(), Math::addExact);
            }
        }
        return balances;
    }

    private static List<String> lines(JournalEntry entry) {
        return entry.postings().stream()
                .map(p -> p.side() + " " + p.account().code() + " " + p.amount().atomicUnits())
                .toList();
    }
}
