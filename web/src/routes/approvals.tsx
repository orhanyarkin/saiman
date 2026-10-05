import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";

import { ApprovalRow } from "@/components/approvals/approval-row";
import { ErrorNotice } from "@/components/error-notice";
import { Button } from "@/components/ui/button";
import { pendingApprovalsQuery } from "@/lib/api/queries";
import { useDocumentTitle } from "@/lib/hooks";

export const Route = createFileRoute("/approvals")({
  component: ApprovalsPage,
});

function ApprovalsPage() {
  useDocumentTitle("Approvals");
  const approvals = useQuery(pendingApprovalsQuery());

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Pending approvals</h1>
      <p className="max-w-prose text-sm">
        Payments above the approval threshold wait here for a human decision. Approving one never
        raises the run&apos;s budget, and an approval that is not decided in time expires.
      </p>

      {approvals.isPending ? (
        <p role="status" aria-busy="true">
          Loading approvals…
        </p>
      ) : approvals.isError ? (
        <div className="space-y-3">
          <ErrorNotice error={approvals.error} />
          <Button
            variant="outline"
            onClick={() => {
              void approvals.refetch();
            }}
          >
            Try again
          </Button>
        </div>
      ) : approvals.data.length === 0 ? (
        <p className="text-muted-foreground">
          Nothing is waiting for approval. This list refreshes every few seconds.
        </p>
      ) : (
        <ul aria-label="Pending approvals" className="space-y-4">
          {approvals.data.map((approval) => (
            <ApprovalRow key={approval.id} approval={approval} />
          ))}
        </ul>
      )}
    </div>
  );
}
