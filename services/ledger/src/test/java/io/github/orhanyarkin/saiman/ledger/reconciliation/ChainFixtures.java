package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.evmrpc.UsdcTransfer;
import io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentBook;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Builders for the pure chain step: books from facts, nets from entries, receipts and evidence. */
final class ChainFixtures {

    /** {@code TestPayment}'s validBefore. */
    static final long VALID_BEFORE = 1_790_000_060L;

    static final ChainReconciler.Settings SETTINGS =
            new ChainReconciler.Settings(Duration.ofMinutes(10), Duration.ofMinutes(30));

    /** A safe block one hour past validBefore (past both graces). */
    static final ChainBlock SAFE_LATE = new ChainBlock(1_000_000, VALID_BEFORE + 3600);

    /** A safe block before validBefore. */
    static final ChainBlock SAFE_EARLY = new ChainBlock(1_000_000, VALID_BEFORE - 60);

    private ChainFixtures() {}

    /** The projection and entries after booking the facts in order. */
    record Books(PaymentProjection projection, List<JournalEntry> entries) {

        static Books of(List<PaymentFact> facts) {
            PaymentProjection p = PaymentProjection.initial(facts.getFirst());
            List<JournalEntry> entries = new ArrayList<>();
            for (PaymentFact fact : facts) {
                PaymentBook.Outcome outcome = PaymentBook.apply(p, fact);
                p = outcome.next();
                entries.addAll(outcome.entries());
            }
            return new Books(p, entries);
        }

        Books plus(@Nullable JournalEntry entry, PaymentProjection next) {
            List<JournalEntry> all = new ArrayList<>(entries);
            if (entry != null) {
                all.add(entry);
            }
            return new Books(next, all);
        }

        ChainReconciler.LedgerNets nets() {
            return netsOf(projection, entries);
        }
    }

    static ChainReconciler.LedgerNets netsOf(PaymentProjection p, List<JournalEntry> entries) {
        String available = ChartOfAccounts.buyerAvailable(p.payer()).code();
        String encumbered = ChartOfAccounts.buyerEncumbered(p.payer()).code();
        String seller = ChartOfAccounts.sellerWallet(p.payTo()).code();
        long buyer = 0;
        long enc = 0;
        long sell = 0;
        for (JournalEntry entry : entries) {
            for (Posting posting : entry.postings()) {
                String code = posting.account().code();
                if (code.equals(available) || code.equals(encumbered)) {
                    buyer += posting.signedAtomic();
                }
                if (code.equals(encumbered)) {
                    enc += posting.signedAtomic();
                }
                if (code.equals(seller)) {
                    sell += posting.signedAtomic();
                }
            }
        }
        return new ChainReconciler.LedgerNets(buyer, enc, sell);
    }

    static String txHash(char fill) {
        return "0x" + String.valueOf(fill).repeat(64);
    }

    /** A successful receipt with one transfer and the payment's AuthorizationUsed. */
    static UsdcReceipt receipt(PaymentProjection p, String tx, long block, String to, long value) {
        return new UsdcReceipt(
                tx,
                block,
                true,
                List.of(new UsdcTransfer(upper(p.payer()), upper(to), value)),
                List.of(p.payer() + ":" + p.nonce()));
    }

    static UsdcReceipt matching(PaymentProjection p, String tx) {
        return receipt(p, tx, 999_000, p.payTo(), p.amount().atomicUnits());
    }

    static ChainReconciler.Evidence evidence(
            ChainBlock safe, Map<String, Optional<UsdcReceipt>> receipts, @Nullable Boolean used) {
        return new ChainReconciler.Evidence(safe, receipts, used, null);
    }

    static ChainReconciler.Evidence evidence(
            ChainBlock safe,
            Map<String, Optional<UsdcReceipt>> receipts,
            @Nullable Boolean used,
            @Nullable UsdcReceipt found) {
        return new ChainReconciler.Evidence(safe, receipts, used, found);
    }

    /** Checksummed-looking input: the step must compare case-insensitively. */
    private static String upper(String address) {
        return "0x" + address.substring(2).toUpperCase(Locale.ROOT);
    }
}
