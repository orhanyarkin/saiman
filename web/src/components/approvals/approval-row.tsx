import { Link } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { Button } from "@/components/ui/button";
import { isReplayMode } from "@/lib/api/source";
import type { ApprovalView } from "@/lib/api/types";
import { useNow } from "@/lib/hooks";
import { formatMoney, moneyTitle, usdc } from "@/lib/money";
import { useApprovalDecision } from "@/lib/use-approval-decision";

/** One pending approval in the cross-run list; same decision logic as the inline run card. */
export function ApprovalRow({ approval }: { approval: ApprovalView }) {
  const { decision, conflict } = useApprovalDecision(approval.runId, approval.id);
  const amount = usdc(approval.amountAtomic);
  const expires = new Date(approval.expiresAt);
  const now = useNow(5000);
  const expired = expires.getTime() <= now;
  const locked = expired || decision.isPending || decision.isSuccess || conflict;
  const label = `${formatMoney(amount)} to ${approval.payTo}`;

  return (
    <li className="space-y-3 rounded-lg border-2 border-amber-600 p-4">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground font-medium">Amount</dt>
        <dd title={moneyTitle(amount)} className="font-semibold">
          {formatMoney(amount)}
        </dd>
        <dt className="text-muted-foreground font-medium">Payee</dt>
        <dd className="font-mono break-all">{approval.payTo}</dd>
        <dt className="text-muted-foreground font-medium">Resource</dt>
        <dd className="break-all">{approval.resource}</dd>
        <dt className="text-muted-foreground font-medium">Expires</dt>
        <dd>
          <time dateTime={approval.expiresAt}>{expires.toLocaleTimeString()}</time>
          {expired ? <strong> (expired)</strong> : null}
        </dd>
        <dt className="text-muted-foreground font-medium">Run</dt>
        <dd>
          <Link
            to="/runs/$runId"
            params={{ runId: approval.runId }}
            className="underline underline-offset-4"
          >
            Open the run
          </Link>
        </dd>
      </dl>
      {conflict ? (
        <p role="status" className="text-sm font-medium">
          This approval was already decided or has expired. Refreshing the list…
        </p>
      ) : decision.isError ? (
        <ErrorNotice error={decision.error} />
      ) : null}
      {decision.isSuccess ? (
        <p role="status" className="text-sm font-medium">
          {decision.data.status === "APPROVED" ? "Approved" : "Rejected"}.
        </p>
      ) : null}
      {isReplayMode ? (
        <p className="text-muted-foreground text-sm">
          Recorded demo: approvals are disabled because nothing here can change state.
        </p>
      ) : (
        <div className="flex gap-3">
          <Button
            disabled={locked}
            aria-label={`Approve ${label}`}
            onClick={() => {
              decision.mutate("APPROVE");
            }}
          >
            Approve
          </Button>
          <Button
            variant="outline"
            disabled={locked}
            aria-label={`Reject ${label}`}
            onClick={() => {
              decision.mutate("REJECT");
            }}
          >
            Reject
          </Button>
        </div>
      )}
    </li>
  );
}
