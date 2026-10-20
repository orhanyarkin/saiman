package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Builds {@link ReconciliationReport}s from the run, item, payment and posting tables. */
@Service
public class ReconciliationReports {

    private static final Money USDC_ZERO = Money.usdc(0);

    private final ReconciliationRepository repository;

    public ReconciliationReports(ReconciliationRepository repository) {
        this.repository = repository;
    }

    public Optional<ReconciliationReport> report(UUID runId) {
        return repository.run(runId).map(this::toReport);
    }

    public Optional<ReconciliationReport> latest() {
        return repository.latestRun().map(this::toReport);
    }

    /** The {@code limit} most recently started runs, newest first, without their items. */
    public ReconciliationRunList history(int limit) {
        return new ReconciliationRunList(repository.runs(limit).stream()
                .map(run -> new ReconciliationRunList.Run(
                        run.id(), run.status(), run.startedAt(), run.finishedAt(), run.safeBlock(), summary(run)))
                .toList());
    }

    private static ReconciliationReport.Summary summary(ReconciliationRepository.Run run) {
        var c = run.counters();
        return new ReconciliationReport.Summary(
                c.checked(), c.matched(), c.pending(), c.resolvedUsed(), c.resolvedUnused(), c.mismatches());
    }

    private ReconciliationReport toReport(ReconciliationRepository.Run run) {
        List<ReconciliationRepository.BookSuspense> balances = repository.suspenseBalances(USDC_ZERO);
        // The per-book figures are already clamped to ±Long.MAX_VALUE, so two saturated books would overflow a long
        // sum. Summed as BigDecimal and clamped once: a flood of forged mismatches must not turn the report into a
        // 500 (which is exactly what suspenseBalances' javadoc promises).
        long suspense = ReconciliationRepository.saturated(balances.stream()
                .map(book -> BigDecimal.valueOf(book.signedAtomic()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<ReconciliationReport.BookSuspense> byBook = balances.stream()
                .map(b -> new ReconciliationReport.BookSuspense(
                        b.book(), Money.usdc(Math.absExact(b.signedAtomic())), side(b.signedAtomic())))
                .toList();
        var items = repository.items(run.id()).stream()
                .map(row -> new ReconciliationReport.Item(
                        row.paymentId(),
                        row.paymentIntentId(),
                        row.runId(),
                        row.payer(),
                        row.payTo(),
                        row.amount(),
                        row.buyerState(),
                        row.sellerState(),
                        row.chainState(),
                        row.txHash(),
                        row.status(),
                        row.mismatchKind() == null
                                ? null
                                : new ReconciliationReport.Mismatch(
                                        row.mismatchKind(),
                                        money(row.ledgerAtomic(), row.amount()),
                                        money(row.chainAtomic(), row.amount()),
                                        row.adjustmentEntryId())))
                .toList();
        return new ReconciliationReport(
                run.id(),
                run.startedAt(),
                run.finishedAt(),
                run.status(),
                run.network(),
                run.safeBlock(),
                summary(run),
                Money.usdc(Math.absExact(suspense)),
                side(suspense),
                byBook,
                items);
    }

    /** DEBIT, CREDIT or null for a zero balance; Money is never negative, so the sign travels separately. */
    private static @Nullable String side(long signedAtomic) {
        return signedAtomic > 0 ? "DEBIT" : signedAtomic < 0 ? "CREDIT" : null;
    }

    private static @Nullable Money money(@Nullable Long atomic, Money like) {
        return atomic == null ? null : new Money(atomic, like.asset(), like.decimals());
    }
}
