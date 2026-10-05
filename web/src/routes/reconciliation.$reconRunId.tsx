import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { ReportView } from "@/components/ledger/reconciliation";
import { buttonVariants } from "@/components/ui/button";
import { reconRunQuery } from "@/lib/api/queries";
import { ApiError } from "@/lib/api/source";
import { useDocumentTitle } from "@/lib/hooks";
import { isUuid } from "@/lib/ledger-model";

export const Route = createFileRoute("/reconciliation/$reconRunId")({
  component: ReconciliationRunPage,
});

function ReconciliationRunPage() {
  const { reconRunId } = Route.useParams();
  useDocumentTitle("Reconciliation run");
  const valid = isUuid(reconRunId);
  const report = useQuery({ ...reconRunQuery(reconRunId), enabled: valid });
  const back = (
    <Link to="/reconciliation" className={buttonVariants({ variant: "outline" })}>
      Back to reconciliation
    </Link>
  );

  if (!valid) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">Reconciliation run not found</h1>
        <p role="alert" className="text-destructive text-sm font-medium">
          <span aria-hidden="true">{"⚠ "}</span>
          This is not a valid reconciliation run id.
        </p>
        {back}
      </div>
    );
  }
  if (report.isPending) {
    return (
      <div className="space-y-4" aria-busy="true">
        <h1 className="text-2xl font-semibold">Reconciliation run</h1>
        <p role="status">Loading report…</p>
      </div>
    );
  }
  if (report.isError) {
    const notFound = report.error instanceof ApiError && report.error.kind === "not-found";
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">
          {notFound ? "Reconciliation run not found" : "Reconciliation run unavailable"}
        </h1>
        <ErrorNotice error={report.error} />
        {back}
      </div>
    );
  }
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Reconciliation run {reconRunId.slice(0, 8)}</h1>
      <ReportView report={report.data} />
      {back}
    </div>
  );
}
