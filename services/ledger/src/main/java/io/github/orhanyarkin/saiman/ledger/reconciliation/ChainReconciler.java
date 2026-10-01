package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.buyerAvailable;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.sellerWallet;
import static io.github.orhanyarkin.saiman.ledger.journal.ChartOfAccounts.suspense;

import io.github.orhanyarkin.saiman.evmrpc.ChainBlock;
import io.github.orhanyarkin.saiman.evmrpc.UsdcReceipt;
import io.github.orhanyarkin.saiman.evmrpc.UsdcTransfer;
import io.github.orhanyarkin.saiman.ledger.journal.Account;
import io.github.orhanyarkin.saiman.ledger.journal.EntryKind;
import io.github.orhanyarkin.saiman.ledger.journal.JournalEntry;
import io.github.orhanyarkin.saiman.ledger.journal.LedgerBook;
import io.github.orhanyarkin.saiman.ledger.journal.Posting;
import io.github.orhanyarkin.saiman.ledger.payment.BuyerState;
import io.github.orhanyarkin.saiman.ledger.payment.ChainState;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentBook;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.SellerState;
import io.github.orhanyarkin.saiman.shared.ledger.MismatchKind;
import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The chain step of the per-payment state machine (ADR-0017, ADR-0018): pure, no Spring, no database, no RPC.
 * Given the payment projection, the ledger's net movements for that payment and what Base Sepolia says at the
 * {@code safe} block, it returns the next projection, the item status, the mismatches and at most one ADJUSTMENT
 * entry that moves every difference to {@code platform:suspense:usdc}.
 *
 * <h2>Chain truth</h2>
 *
 * <ol>
 *   <li>Two different reported tx hashes (buyer vs seller): {@code CONFLICTING_TX}; nothing else is decided and
 *       nothing is posted until a human looks.
 *   <li>One reported tx hash: its receipt must exist ({@code TX_NOT_FOUND} once the safe block is past
 *       {@code validBefore + receiptGrace}, PENDING before that), be at or below the safe block (PENDING
 *       otherwise), have succeeded ({@code TX_FAILED}) and contain {@code AuthorizationUsed(payer, nonce)}
 *       ({@code TX_NOT_FOR_AUTHORIZATION}). A receipt passing all of that is the <em>canonical</em> transfer.
 *   <li>No canonical receipt yet: before {@code validBefore} (at the safe block) the authorization may still be
 *       used, so the item is PENDING. After it, {@code authorizationState} at the safe block decides: UNUSED means
 *       no money moved; USED with a canonical receipt found by log lookup means that receipt; USED without one is
 *       {@code TX_UNKNOWN} (information, not a mismatch: nothing is compared or posted).
 *   <li>A canonical receipt without a {@code Transfer(payer, payTo)} is a {@code PARTY_MISMATCH}; one whose value
 *       differs from the authorization is an {@code AMOUNT_MISMATCH}.
 * </ol>
 *
 * <h2>Suspense rule</h2>
 *
 * The chain is right. For every <em>known</em> wallet — the buyer's once the buyer's book is terminal (SETTLED or
 * RELEASED), the seller's once the seller's book has reported (SETTLED or SETTLE_FAILED) — the ledger's net
 * movement over <em>all</em> entries of this payment (adjustments included) is compared with the chain's movement.
 * A difference is posted between that wallet and suspense, in one ADJUSTMENT entry per run. Because adjustments
 * count towards the net, a rerun finds no difference and posts nothing. A book that has not reported is not
 * compared (M3 history has buyer events only). The difference is named by the receipt finding when there is one
 * (PARTY/AMOUNT), else: chain UNUSED → {@code SETTLED_BUT_UNUSED}; chain USED while the book says released or
 * failed → {@code UNUSED_BUT_SETTLED}; otherwise {@code AMOUNT_MISMATCH}. Naming is {@code <books>_BUT_<chain>}.
 *
 * <p>Independently of the chain, a terminal buyer book whose encumbered account still carries a balance for the
 * payment is an {@code ENCUMBRANCE_NOT_CLEARED} (an internal check; nothing is posted).
 */
public final class ChainReconciler {

    private ChainReconciler() {}

    /** The graces of {@code saiman.ledger.reconciliation}. */
    public record Settings(Duration receiptGrace, Duration graceAfterValidBefore) {}

    /**
     * What the chain said, all read at or relative to {@code safe}.
     *
     * @param receipts receipt lookups for the reported tx hashes (empty optional: not found)
     * @param authorizationUsed {@code authorizationState} at the safe block, or null if not read
     * @param foundReceipt the receipt of the tx found by {@code findAuthorizationTx}, or null
     */
    public record Evidence(
            ChainBlock safe,
            Map<String, Optional<UsdcReceipt>> receipts,
            @Nullable Boolean authorizationUsed,
            @Nullable UsdcReceipt foundReceipt) {

        public Evidence {
            receipts = Map.copyOf(receipts);
        }
    }

    /**
     * The ledger's net movement ({@code debit - credit}, atomic units) for this payment's entries only.
     *
     * @param buyerWallet buyer {@code wallet:available} plus {@code wallet:encumbered}: the buyer's on-chain wallet
     * @param buyerEncumbered buyer {@code wallet:encumbered} alone
     * @param sellerWallet seller {@code wallet}
     */
    public record LedgerNets(long buyerWallet, long buyerEncumbered, long sellerWallet) {}

    /** One difference found; amounts are absolute values (Money is never negative). */
    public record Finding(
            MismatchKind kind,
            @Nullable Money ledgerValue,
            @Nullable Money chainValue,
            @Nullable String reportedTxHash,
            @Nullable String chainTxHash,
            boolean adjusted) {}

    /**
     * The result of one check.
     *
     * @param next the projection with chain state, chain tx hash and {@code lastCheckedAt}
     * @param chainBlock block of the canonical receipt, if any
     * @param findings mismatches, the one carrying the adjustment (if any) first
     * @param adjustment the ADJUSTMENT entry to post, or null
     */
    public record Outcome(
            ItemStatus status,
            PaymentProjection next,
            @Nullable Long chainBlock,
            List<Finding> findings,
            @Nullable JournalEntry adjustment) {

        public Outcome {
            findings = List.copyOf(findings);
        }

        /** The finding the report shows for the item: the adjusted one, else the first. */
        public @Nullable Finding primary() {
            return findings.isEmpty() ? null : findings.getFirst();
        }
    }

    /** The tx hashes producers reported for the payment, lower-case, without duplicates. */
    public static List<String> reportedTxHashes(PaymentProjection p) {
        Set<String> hashes = new LinkedHashSet<>();
        if (p.buyerTxHash() != null) {
            hashes.add(p.buyerTxHash());
        }
        if (p.sellerTxHash() != null) {
            hashes.add(p.sellerTxHash());
        }
        return List.copyOf(hashes);
    }

    /**
     * Whether the caller should read {@code authorizationState} (and, if used, look up the tx): only when no
     * reported receipt is canonical and the safe block is past {@code validBefore}.
     */
    public static boolean needsAuthorizationState(
            PaymentProjection p, Map<String, Optional<UsdcReceipt>> receipts, ChainBlock safe) {
        if (reportedTxHashes(p).size() > 1 || safe.timestamp() <= p.validBefore()) {
            return false;
        }
        return receipts.values().stream().flatMap(Optional::stream).noneMatch(r -> isCanonical(r, p, safe));
    }

    /** Checks one payment against the chain. {@code runId} names the adjustment entry, so a rerun gets a new one. */
    public static Outcome reconcile(
            PaymentProjection p, LedgerNets nets, Evidence evidence, Settings settings, UUID runId, Instant now) {
        List<Finding> findings = new ArrayList<>();
        boolean buyerTerminal = p.buyerState() == BuyerState.SETTLED || p.buyerState() == BuyerState.RELEASED;
        if (buyerTerminal && nets.buyerEncumbered() != 0) {
            findings.add(new Finding(
                    MismatchKind.ENCUMBRANCE_NOT_CLEARED,
                    money(p, nets.buyerEncumbered()),
                    p.amount().zero(),
                    null,
                    null,
                    false));
        }

        ChainBlock safe = evidence.safe();
        List<String> reported = reportedTxHashes(p);
        if (reported.size() > 1) {
            findings.add(
                    new Finding(MismatchKind.CONFLICTING_TX, null, null, p.buyerTxHash(), p.sellerTxHash(), false));
            return new Outcome(
                    ItemStatus.MISMATCH, p.withChain(p.chainState(), p.chainTxHash(), now), null, findings, null);
        }

        String reportedTx = reported.isEmpty() ? null : reported.getFirst();
        UsdcReceipt canonical = null;
        if (reportedTx != null) {
            Optional<UsdcReceipt> lookup = evidence.receipts().get(reportedTx);
            if (lookup == null) {
                return pending(p, findings, now);
            }
            if (lookup.isEmpty()) {
                if (safe.timestamp()
                        <= p.validBefore() + settings.receiptGrace().toSeconds()) {
                    return pending(p, findings, now);
                }
                findings.add(new Finding(MismatchKind.TX_NOT_FOUND, null, null, reportedTx, null, false));
            } else {
                UsdcReceipt receipt = lookup.get();
                if (receipt.blockNumber() > safe.number()) {
                    return pending(p, findings, now);
                } else if (!receipt.succeeded()) {
                    findings.add(new Finding(
                            MismatchKind.TX_FAILED, null, null, reportedTx, lower(receipt.txHash()), false));
                } else if (!usesAuthorization(receipt, p)) {
                    findings.add(new Finding(
                            MismatchKind.TX_NOT_FOR_AUTHORIZATION,
                            null,
                            null,
                            reportedTx,
                            lower(receipt.txHash()),
                            false));
                } else {
                    canonical = receipt;
                }
            }
        }

        ChainState chainState;
        if (canonical == null) {
            if (safe.timestamp() <= p.validBefore() || evidence.authorizationUsed() == null) {
                // The authorization can still be used (or its state was not read): decide nothing yet.
                return pending(p, findings, now);
            }
            if (evidence.authorizationUsed()) {
                UsdcReceipt found = evidence.foundReceipt();
                if (found != null && isCanonical(found, p, safe)) {
                    canonical = found;
                } else {
                    PaymentProjection next = p.withChain(ChainState.USED, p.chainTxHash(), now);
                    return new Outcome(
                            findings.isEmpty() ? ItemStatus.TX_UNKNOWN : ItemStatus.MISMATCH,
                            next,
                            null,
                            findings,
                            null);
                }
            }
        }

        long chainBuyer = 0;
        long chainSeller = 0;
        Finding receiptFinding = null;
        String chainTx = null;
        Long chainBlock = null;
        if (canonical != null) {
            chainState = ChainState.USED;
            chainTx = lower(canonical.txHash());
            chainBlock = canonical.blockNumber();
            long fromPayer = 0;
            long direct = 0;
            boolean anyDirect = false;
            for (UsdcTransfer t : canonical.transfers()) {
                if (lower(t.from()).equals(p.payer())) {
                    fromPayer = Math.addExact(fromPayer, t.value());
                    if (lower(t.to()).equals(p.payTo())) {
                        direct = Math.addExact(direct, t.value());
                        anyDirect = true;
                    }
                }
            }
            chainBuyer = -fromPayer;
            chainSeller = direct;
            if (!anyDirect) {
                receiptFinding = new Finding(
                        MismatchKind.PARTY_MISMATCH, p.amount(), money(p, fromPayer), reportedTx, chainTx, false);
            } else if (direct != p.amount().atomicUnits()) {
                receiptFinding = new Finding(
                        MismatchKind.AMOUNT_MISMATCH, p.amount(), money(p, direct), reportedTx, chainTx, false);
            }
        } else {
            chainState = ChainState.UNUSED;
        }

        // Suspense rule: compare each known wallet's net with the chain; post the differences.
        List<Posting> legs = new ArrayList<>();
        Money ledgerShown = null;
        Money chainShown = null;
        if (buyerTerminal) {
            long diff = Math.subtractExact(nets.buyerWallet(), chainBuyer);
            if (diff != 0) {
                addLegs(legs, buyerAvailable(p.payer()), diff, p);
                ledgerShown = money(p, nets.buyerWallet());
                chainShown = money(p, chainBuyer);
            }
        }
        if (p.sellerState() != SellerState.NONE) {
            long diff = Math.subtractExact(nets.sellerWallet(), chainSeller);
            if (diff != 0) {
                addLegs(legs, sellerWallet(p.payTo()), diff, p);
                if (ledgerShown == null) {
                    ledgerShown = money(p, nets.sellerWallet());
                    chainShown = money(p, chainSeller);
                }
            }
        }

        JournalEntry adjustment = null;
        if (!legs.isEmpty()) {
            MismatchKind kind;
            if (receiptFinding != null) {
                kind = receiptFinding.kind();
            } else if (chainState == ChainState.UNUSED) {
                kind = MismatchKind.SETTLED_BUT_UNUSED;
            } else if (p.buyerState() == BuyerState.RELEASED || p.sellerState() == SellerState.SETTLE_FAILED) {
                kind = MismatchKind.UNUSED_BUT_SETTLED;
            } else {
                kind = MismatchKind.AMOUNT_MISMATCH;
            }
            adjustment = new JournalEntry(
                    PaymentBook.entryId(p.paymentKey(), LedgerBook.PLATFORM, "ADJUSTMENT:" + runId),
                    p.id(),
                    p.paymentKey(),
                    LedgerBook.PLATFORM,
                    EntryKind.ADJUSTMENT,
                    null,
                    null,
                    "Reconciliation: " + kind + " moved to suspense",
                    now,
                    legs);
            Finding adjusted = receiptFinding != null
                    ? new Finding(
                            kind, receiptFinding.ledgerValue(), receiptFinding.chainValue(), reportedTx, chainTx, true)
                    : new Finding(kind, ledgerShown, chainShown, reportedTx, chainTx, true);
            findings.addFirst(adjusted);
        } else if (receiptFinding != null) {
            findings.add(receiptFinding);
        }

        PaymentProjection next = p.withChain(chainState, chainTx != null ? chainTx : p.chainTxHash(), now);
        return new Outcome(
                findings.isEmpty() ? ItemStatus.MATCHED : ItemStatus.MISMATCH, next, chainBlock, findings, adjustment);
    }

    private static Outcome pending(PaymentProjection p, List<Finding> findings, Instant now) {
        return new Outcome(
                findings.isEmpty() ? ItemStatus.PENDING : ItemStatus.MISMATCH,
                p.withChain(p.chainState(), p.chainTxHash(), now),
                null,
                findings,
                null);
    }

    /**
     * {@code diff = ledger - chain}. Positive: the ledger debited the wallet more than the chain moved, so credit
     * the wallet and debit suspense; negative: the other way round.
     */
    private static void addLegs(List<Posting> legs, Account wallet, long diff, PaymentProjection p) {
        Money amount = money(p, diff);
        Account suspense = suspense(amount);
        if (diff > 0) {
            legs.add(Posting.debit(suspense, amount));
            legs.add(Posting.credit(wallet, amount));
        } else {
            legs.add(Posting.debit(wallet, amount));
            legs.add(Posting.credit(suspense, amount));
        }
    }

    private static boolean isCanonical(UsdcReceipt receipt, PaymentProjection p, ChainBlock safe) {
        return receipt.succeeded() && receipt.blockNumber() <= safe.number() && usesAuthorization(receipt, p);
    }

    private static boolean usesAuthorization(UsdcReceipt receipt, PaymentProjection p) {
        String wanted = p.payer() + ":" + p.nonce();
        return receipt.authorizationsUsed().stream().map(ChainReconciler::lower).anyMatch(wanted::equals);
    }

    private static Money money(PaymentProjection p, long signed) {
        return new Money(Math.absExact(signed), p.amount().asset(), p.amount().decimals());
    }

    private static String lower(String value) {
        return PaymentProjection.lower(value);
    }
}
