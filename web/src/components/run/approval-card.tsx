import { ErrorNotice } from "@/components/error-notice";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { isReplayMode } from "@/lib/api/source";
import { useNow } from "@/lib/hooks";
import { formatMoney, moneyTitle } from "@/lib/money";
import { useApprovalDecision } from "@/lib/use-approval-decision";
import type { ApprovalRequest } from "@/lib/run-view-model";

function countdown(msLeft: number): string {
  const total = Math.max(0, Math.floor(msLeft / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${String(minutes)}:${String(seconds).padStart(2, "0")}`;
}

interface Props {
  runId: string;
  approval: ApprovalRequest;
  /** Called after a decision was sent or the server said it is already settled (409): refetch. */
  onSettled: () => void;
}

/**
 * The inline approval card: a labelled region plus one static, visually hidden `role="alert"`
 * line, so the approval is announced once, assertively, when it appears (the buttons stay outside
 * the alert). The ticking countdown is `aria-hidden`; screen readers get the fixed expiry time
 * instead, so nothing is announced every second.
 */
export function ApprovalCard({ runId, approval, onSettled }: Props) {
  const now = useNow(1000);
  const expiresAt = Date.parse(approval.expiresAt);
  const expired = now >= expiresAt;

  const { decision, conflict } = useApprovalDecision(runId, approval.approvalId, onSettled);
  const locked = expired || decision.isPending || decision.isSuccess || conflict;
  const expiresText = new Date(expiresAt).toLocaleTimeString();

  return (
    <Card role="region" aria-labelledby="approval-title" className="border-2 border-amber-600">
      {/* One static, assertive announcement when the card appears (the card is keyed by approval). */}
      <p role="alert" className="sr-only">
        Approval needed: pay {formatMoney(approval.amount)} to {approval.payTo}
      </p>
      <CardHeader>
        <CardTitle>
          <h2 id="approval-title">Approval needed: pay {formatMoney(approval.amount)}</h2>
        </CardTitle>
        <CardDescription>
          This payment is above the approval threshold. Approving it does not raise the run budget.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted-foreground font-medium">Amount</dt>
          <dd title={moneyTitle(approval.amount)}>{formatMoney(approval.amount)}</dd>
          <dt className="text-muted-foreground font-medium">Payee</dt>
          <dd className="font-mono break-all">{approval.payTo}</dd>
          <dt className="text-muted-foreground font-medium">Resource</dt>
          <dd className="break-all">{approval.resource}</dd>
          <dt className="text-muted-foreground font-medium">Expires</dt>
          <dd>
            <span>at {expiresText}</span>{" "}
            {expired ? (
              <strong>(expired)</strong>
            ) : (
              <span aria-hidden="true">({countdown(expiresAt - now)} left)</span>
            )}
          </dd>
        </dl>
        {expired && !decision.isSuccess ? (
          <p className="text-sm font-medium">
            This approval has expired. The run will deny the payment and carry on.
          </p>
        ) : null}
        {conflict ? (
          <p className="text-sm font-medium">
            This approval was already decided or has expired. Refreshing the run…
          </p>
        ) : decision.isError ? (
          <ErrorNotice error={decision.error} />
        ) : null}
        {decision.isSuccess ? (
          <p role="status" className="text-sm font-medium">
            {decision.data.status === "APPROVED" ? "Approved" : "Rejected"}. Waiting for the run to
            continue…
          </p>
        ) : null}
      </CardContent>
      <CardFooter className="gap-3">
        {isReplayMode ? (
          <p className="text-muted-foreground text-sm">
            Recorded demo: approvals are disabled because nothing here can change state.
          </p>
        ) : (
          <>
            <Button
              disabled={locked}
              onClick={() => {
                decision.mutate("APPROVE");
              }}
            >
              Approve {formatMoney(approval.amount)}
            </Button>
            <Button
              variant="outline"
              disabled={locked}
              onClick={() => {
                decision.mutate("REJECT");
              }}
            >
              Reject
            </Button>
          </>
        )}
      </CardFooter>
    </Card>
  );
}
