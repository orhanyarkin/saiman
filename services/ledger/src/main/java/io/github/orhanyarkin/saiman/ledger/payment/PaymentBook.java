package io.github.orhanyarkin.saiman.ledger.payment;

import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.buyerAvailable;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.buyerEncumbered;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.buyerExpense;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.sellerCreditNotes;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.sellerCustomerCredits;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.sellerRevenue;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.sellerWallet;
import static io.github.orhanyarkin.saiman.ledger.journal.Posting.credit;
import static io.github.orhanyarkin.saiman.ledger.journal.Posting.debit;

import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.LedgerBook;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.Finality;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The per-payment state machine (ADR-0017): pure, no Spring, no database. Given the current projection and one
 * fact it returns the next projection and the journal entries that fact makes due and that are not posted yet.
 *
 * <p>Order independence (ADR-0016) follows from the shape: each book's state is the {@linkplain BuyerState#join
 * join} of every fact seen so far, and the entries posted are a function of that state only. So a {@code settled}
 * before its {@code authorized} posts ENCUMBER and SETTLE, the late {@code authorized} posts nothing, and a
 * duplicate posts nothing. The one non-monotone step, RELEASED followed by SETTLED (contradictory producers; the
 * chain wins), posts a REVERSAL of the RELEASE, so the balances still equal those of the canonical order.
 *
 * <p>Credit notes (ADR-0021) follow the same shape: {@code CREDITED} implies SALE and CREDIT_NOTE, so a credit note
 * before its {@code settled} posts both. Two seller reports that cannot both be true are rejected as a {@link
 * ConflictingFactException} (nothing posted, reported for a human), whichever arrives second: a credit note and a
 * settle failure with no settled report in between, or a credit note whose tx hash differs from the settled one.
 *
 * <p>Entry ids are name-based UUIDs of {@code (payment key, book, kind)}, matching the database's unique index:
 * replays produce the same ids.
 */
public final class PaymentBook {

    private PaymentBook() {}

    /** The next projection plus the entries to post, in posting order. */
    public record Outcome(PaymentProjection next, List<JournalEntry> entries) {
        public Outcome {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Applies one fact.
     *
     * @throws ConflictingFactException if the fact disagrees with the projection about the signed authorization
     */
    public static Outcome apply(PaymentProjection current, PaymentFact fact) {
        requireConsistent(current, fact);
        PaymentProjection next = current.with(
                current.buyerState().join(buyerTarget(fact)),
                current.sellerState().join(sellerTarget(fact)),
                firstNonNull(current.paymentIntentId(), fact.paymentIntentId()),
                firstNonNull(current.runId(), fact.runId()),
                firstNonNull(current.buyerTxHash(), txHash(fact, Book.BUYER)),
                firstNonNull(current.sellerTxHash(), txHash(fact, Book.SELLER)));

        List<JournalEntry> entries = new ArrayList<>();
        Set<EntryKind> buyerBefore = current.buyerState().impliedEntries();
        Set<EntryKind> buyerAfter = next.buyerState().impliedEntries();
        for (EntryKind kind : List.of(EntryKind.ENCUMBER, EntryKind.RELEASE, EntryKind.SETTLE)) {
            if (buyerAfter.contains(kind) && !buyerBefore.contains(kind)) {
                entries.add(entry(next, LedgerBook.BUYER, kind, fact, null, postings(next, kind)));
            }
        }
        if (buyerBefore.contains(EntryKind.RELEASE) && !buyerAfter.contains(EntryKind.RELEASE)) {
            List<Posting> mirrored = postings(next, EntryKind.RELEASE).stream()
                    .map(Posting::reversed)
                    .toList();
            entries.add(entry(
                    next,
                    LedgerBook.BUYER,
                    EntryKind.REVERSAL,
                    fact,
                    entryId(next.paymentKey(), LedgerBook.BUYER, EntryKind.RELEASE.name()),
                    mirrored));
        }
        Set<EntryKind> sellerBefore = current.sellerState().impliedEntries();
        Set<EntryKind> sellerAfter = next.sellerState().impliedEntries();
        for (EntryKind kind : List.of(EntryKind.SALE, EntryKind.CREDIT_NOTE)) {
            if (sellerAfter.contains(kind) && !sellerBefore.contains(kind)) {
                entries.add(entry(next, LedgerBook.SELLER, kind, fact, null, postings(next, kind)));
            }
        }
        return new Outcome(next, entries);
    }

    /** The id an entry of {@code kind} for this payment and book has (REVERSAL: {@code "REVERSAL:<kind>"}). */
    public static UUID entryId(String paymentKey, LedgerBook book, String kind) {
        return UUID.nameUUIDFromBytes(
                ("saiman-ledger:entry:" + paymentKey + ":" + book + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    private static BuyerState buyerTarget(PaymentFact fact) {
        return switch (fact) {
            case PaymentFact.Authorized a -> BuyerState.AUTHORIZED;
            case PaymentFact.Settled s -> s.book() == Book.BUYER ? BuyerState.SETTLED : BuyerState.NONE;
            case PaymentFact.CreditNoted c -> BuyerState.NONE;
            case PaymentFact.Failed f -> {
                if (f.book() != Book.BUYER) {
                    yield BuyerState.NONE;
                }
                // FINAL: expired unused (read on chain). AMBIGUOUS still proves a signed authorization exists.
                yield f.event().finality() == Finality.FINAL ? BuyerState.RELEASED : BuyerState.AUTHORIZED;
            }
        };
    }

    private static SellerState sellerTarget(PaymentFact fact) {
        return switch (fact) {
            case PaymentFact.Authorized a -> SellerState.NONE;
            case PaymentFact.Settled s -> s.book() == Book.SELLER ? SellerState.SETTLED : SellerState.NONE;
            case PaymentFact.Failed f -> f.book() == Book.SELLER ? SellerState.SETTLE_FAILED : SellerState.NONE;
            case PaymentFact.CreditNoted c -> SellerState.CREDITED;
        };
    }

    private static @Nullable String txHash(PaymentFact fact, Book book) {
        if (fact instanceof PaymentFact.CreditNoted c && book == Book.SELLER) {
            return PaymentProjection.lower(c.txHash());
        }
        if (fact instanceof PaymentFact.Settled s
                && s.book() == book
                && s.event().txHash() != null) {
            return PaymentProjection.lower(Objects.requireNonNull(s.event().txHash()));
        }
        return null;
    }

    private static List<Posting> postings(PaymentProjection p, EntryKind kind) {
        var amount = p.amount();
        return switch (kind) {
            case ENCUMBER ->
                List.of(debit(buyerEncumbered(p.payer()), amount), credit(buyerAvailable(p.payer()), amount));
            case SETTLE -> List.of(debit(buyerExpense(p.payer()), amount), credit(buyerEncumbered(p.payer()), amount));
            case RELEASE ->
                List.of(debit(buyerAvailable(p.payer()), amount), credit(buyerEncumbered(p.payer()), amount));
            case SALE -> List.of(debit(sellerWallet(p.payTo()), amount), credit(sellerRevenue(p.payTo()), amount));
            case CREDIT_NOTE ->
                List.of(debit(sellerCreditNotes(p.payTo()), amount), credit(sellerCustomerCredits(p.payTo()), amount));
            case ADJUSTMENT, REVERSAL, LLM_USAGE ->
                throw new IllegalArgumentException(kind + " is not posted by the payment state machine");
        };
    }

    private static JournalEntry entry(
            PaymentProjection p,
            LedgerBook book,
            EntryKind kind,
            PaymentFact fact,
            @Nullable UUID reverses,
            List<Posting> postings) {
        String idKind = reverses == null ? kind.name() : kind.name() + ":" + EntryKind.RELEASE.name();
        return new JournalEntry(
                entryId(p.paymentKey(), book, idKind),
                p.id(),
                p.paymentKey(),
                book,
                kind,
                fact.meta().eventId(),
                reverses,
                description(kind),
                fact.meta().occurredAt(),
                postings);
    }

    private static String description(EntryKind kind) {
        return switch (kind) {
            case ENCUMBER -> "Buyer signed an authorization: funds encumbered";
            case SETTLE -> "Buyer payment settled: encumbrance expensed";
            case RELEASE -> "Authorization expired unused: encumbrance released";
            case SALE -> "Seller settled a paid request";
            case REVERSAL -> "Release reversed: the authorization was used after all";
            case CREDIT_NOTE -> "Seller settled up front but did not serve: full amount credited to the buyer";
            case ADJUSTMENT, LLM_USAGE -> kind.name();
        };
    }

    private static void requireConsistent(PaymentProjection p, PaymentFact fact) {
        var auth = fact.authorization();
        if (!p.paymentKey().equals(auth.paymentKey())) {
            throw new IllegalArgumentException("fact is about another payment");
        }
        if (!p.amount().equals(fact.amount())) {
            throw new ConflictingFactException("amount differs from the one already recorded for this payment", p.id());
        }
        if (!p.payTo().equals(PaymentProjection.lower(fact.payTo()))) {
            throw new ConflictingFactException("payTo differs from the one already recorded for this payment", p.id());
        }
        if (p.validBefore() != auth.validBefore()) {
            throw new ConflictingFactException(
                    "validBefore differs from the one already recorded for this payment", p.id());
        }
        requireConsistentSellerReports(p, fact);
    }

    /**
     * Seller reports that contradict a credit note (ADR-0021). The rule is symmetric, so the conflict is reported
     * in either delivery order: a credit note proves a successful settlement, a settle failure says there was none
     * (unless a {@code settled} report already overruled the failure), and the credit note's tx hash is the
     * settlement's. Two plain {@code settled} reports with different hashes stay as before (first one kept).
     */
    private static void requireConsistentSellerReports(PaymentProjection p, PaymentFact fact) {
        switch (fact) {
            case PaymentFact.CreditNoted c -> {
                if (p.sellerState() == SellerState.SETTLE_FAILED) {
                    throw new ConflictingFactException("credit note for a payment whose settlement failed", p.id());
                }
                requireSameSellerTx(p, c.txHash());
            }
            // Deliberately asymmetric: a seller `failed` after SETTLED is accepted ("settled wins" is the M4 rule,
            // the failure report was an ambiguous outcome the settlement later resolved), but after CREDITED it is a
            // conflict, because a credit note asserts that the seller settled AND then failed to serve.
            case PaymentFact.Failed f
            when f.book() == Book.SELLER && p.sellerState() == SellerState.CREDITED ->
                throw new ConflictingFactException("settle failure for a payment the seller credited", p.id());
            case PaymentFact.Settled s
            when s.book() == Book.SELLER
                    && p.sellerState() == SellerState.CREDITED
                    && s.event().txHash() != null ->
                requireSameSellerTx(p, Objects.requireNonNull(s.event().txHash()));
            default -> {}
        }
    }

    private static void requireSameSellerTx(PaymentProjection p, String txHash) {
        String recorded = p.sellerTxHash();
        if (recorded != null && !recorded.equals(PaymentProjection.lower(txHash))) {
            throw new ConflictingFactException(
                    "seller tx hash differs between the credit note and the settlement", p.id());
        }
    }

    private static <T> @Nullable T firstNonNull(@Nullable T first, @Nullable T second) {
        return first != null ? first : second;
    }
}
