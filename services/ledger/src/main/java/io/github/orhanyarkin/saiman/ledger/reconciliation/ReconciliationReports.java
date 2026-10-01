package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.github.orhanyarkin.saiman.shared.money.Money;
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

    private ReconciliationReport toReport(ReconciliationRepository.Run run) {
        var c = run.counters();
        long suspense = repository.suspenseBalance(USDC_ZERO);
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
                new ReconciliationReport.Summary(
                        c.checked(), c.matched(), c.pending(), c.resolvedUsed(), c.resolvedUnused(), c.mismatches()),
                Money.usdc(Math.absExact(suspense)),
                suspense > 0 ? "DEBIT" : suspense < 0 ? "CREDIT" : null,
                items);
    }

    private static @Nullable Money money(@Nullable Long atomic, Money like) {
        return atomic == null ? null : new Money(atomic, like.asset(), like.decimals());
    }
}
