import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { ReportView, RunHistory, RunNowControl } from "@/components/ledger/reconciliation";
import { reconRunQuery, reconRunsQuery } from "@/lib/api/queries";
import { ApiError } from "@/lib/api/source";
import { useDocumentTitle } from "@/lib/hooks";

export const Route = createFileRoute("/reconciliation/")({
  component: ReconciliationPage,
});

function ReconciliationPage() {
  useDocumentTitle("Reconciliation");
  const latest = useQuery(reconRunQuery("latest"));
  const history = useQuery(reconRunsQuery());
  const none = latest.error instanceof ApiError && latest.error.kind === "not-found";

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold">Reconciliation</h1>
      <p className="text-muted-foreground text-sm">
        Reconciliation compares the books with what actually happened on Base Sepolia. The chain is
        treated as the truth: a difference is parked in a suspense account and reported here.
      </p>
      <RunNowControl />

      <section aria-labelledby="latest-title" className="space-y-4">
        <h2 id="latest-title" className="text-xl font-semibold">
          Latest report
        </h2>
        {latest.isPending ? (
          <p role="status" aria-busy="true">
            Loading report…
          </p>
        ) : none ? (
          <p className="text-muted-foreground">
            No reconciliation has run yet. Press "Run now" to start the first one.
          </p>
        ) : latest.isError ? (
          <ErrorNotice error={latest.error} />
        ) : (
          <ReportView report={latest.data} />
        )}
      </section>

      <section aria-labelledby="history-title" className="space-y-4">
        <h2 id="history-title" className="text-xl font-semibold">
          History
        </h2>
        {history.isPending ? (
          <p role="status" aria-busy="true">
            Loading history…
          </p>
        ) : history.isError ? (
          <ErrorNotice error={history.error} />
        ) : history.data.items.length === 0 ? (
          <p className="text-muted-foreground">No runs yet.</p>
        ) : (
          <RunHistory runs={history.data.items} />
        )}
      </section>
    </div>
  );
}
