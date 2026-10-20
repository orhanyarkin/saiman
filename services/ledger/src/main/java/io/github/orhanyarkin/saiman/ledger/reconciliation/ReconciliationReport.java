package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The reconciliation report of one run, as {@code GET /api/v1/reconciliation/runs/{id|latest}} returns it and
 * {@code make recon-report} saves it. Never carries a nonce (the payment id stands for the authorization).
 *
 * @param status RUNNING, COMPLETED, PARTIAL (some items skipped: RPC unavailable) or FAILED (no safe block)
 * @param suspense the absolute balance of every suspense account summed (ADR-0029), when the report is read
 * @param suspenseSide DEBIT when the ledger booked more than the chain moved (the usual tamper case), CREDIT for the
 *     opposite, null when the balance is zero (Money is never negative, so the sign travels separately)
 * @param suspenseByBook the same figure per book, ordered by book: an unresolved difference belongs to the book that
 *     owns it (ADR-0029). The retired {@code platform:suspense:usdc} appears as PLATFORM, netting to zero.
 */
public record ReconciliationReport(
        UUID runId,
        Instant startedAt,
        @Nullable Instant finishedAt,
        String status,
        String network,
        @Nullable Long safeBlock,
        Summary summary,
        Money suspense,
        @Nullable String suspenseSide,
        List<BookSuspense> suspenseByBook,
        List<Item> items) {

    public ReconciliationReport {
        items = List.copyOf(items);
        suspenseByBook = List.copyOf(suspenseByBook);
    }

    /**
     * One book's suspense balance (ADR-0029).
     *
     * @param book BUYER, SELLER or PLATFORM
     * @param balance the absolute balance of that book's suspense accounts
     * @param side DEBIT, CREDIT, or null when the balance is zero, like {@link #suspenseSide()}
     */
    @Schema(name = "ReconciliationBookSuspense")
    public record BookSuspense(
            String book, Money balance, @Nullable String side) {}

    /** Counters of the run. */
    @Schema(name = "ReconciliationCounters")
    public record Summary(
            int checked, int matched, int pending, int resolvedUsed, int resolvedUnused, int mismatches) {}

    /**
     * One payment checked by the run.
     *
     * @param runId the agent run that paid (from the payment events), not the reconciliation run
     * @param status MATCHED, PENDING, MISMATCH or TX_UNKNOWN
     */
    @Schema(name = "ReconciliationItem")
    public record Item(
            UUID paymentId,
            @Nullable UUID paymentIntentId,
            @Nullable UUID runId,
            String payer,
            String payTo,
            Money amount,
            String buyerState,
            String sellerState,
            String chainState,
            @Nullable String txHash,
            String status,
            @Nullable Mismatch mismatch) {}

    /**
     * The main finding of an item (the one that moved money to suspense, if any).
     *
     * @param ledgerValue what the books say (absolute)
     * @param chainValue what the chain says (absolute)
     * @param adjustmentEntryId the ADJUSTMENT entry posted for it in this run, if any
     */
    @Schema(name = "ReconciliationMismatch")
    public record Mismatch(
            String kind,
            @Nullable Money ledgerValue,
            @Nullable Money chainValue,
            @Nullable UUID adjustmentEntryId) {}
}
