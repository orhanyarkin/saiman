import { useQueryClient } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { ApprovalCard } from "@/components/run/approval-card";
import { BudgetMeter } from "@/components/run/budget-meter";
import { PaymentsPanel } from "@/components/run/payments-panel";
import { ReportPanel } from "@/components/run/report-panel";
import { Stepper } from "@/components/run/stepper";
import { Timeline } from "@/components/run/timeline";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { queryKeys } from "@/lib/api/queries";
import { ApiError } from "@/lib/api/source";
import { isTerminalStatus, type RunStatus } from "@/lib/api/types";
import { useDocumentTitle, useRunEvents, useThrottledValue } from "@/lib/hooks";
import { formatMoney, moneyTitle } from "@/lib/money";
import { deriveRunView, stepLabel } from "@/lib/run-view-model";

export const Route = createFileRoute("/runs/$runId")({
  component: RunPage,
});

const STATUS_TEXT: Record<RunStatus, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  AWAITING_APPROVAL: "Waiting for your approval",
  SUCCEEDED: "Completed",
  FAILED: "Failed",
};

function RunPage() {
  const { runId } = Route.useParams();
  // key: a fresh state per run id, so events never leak between runs.
  return <RunView key={runId} runId={runId} />;
}

function RunView({ runId }: { runId: string }) {
  const queryClient = useQueryClient();
  const { events, summary, stream } = useRunEvents(runId);
  const view = deriveRunView(events);
  const status = summary.data?.status;

  useDocumentTitle(status ? `${STATUS_TEXT[status]}: run` : "Run");

  const announcement = useThrottledValue(
    view.pendingApproval
      ? ""
      : view.terminal
        ? view.report
          ? "The run is complete."
          : "The run failed."
        : view.currentStep
          ? `${stepLabel(view.currentStep)} is in progress.`
          : "",
  );

  const refetchRun = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.run(runId) });
  };

  if (summary.isPending) {
    return (
      <div className="space-y-4" aria-busy="true">
        <h1 className="text-2xl font-semibold">Run</h1>
        <p role="status">Loading run…</p>
      </div>
    );
  }
  if (summary.isError) {
    const notFound = summary.error instanceof ApiError && summary.error.kind === "not-found";
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">{notFound ? "Run not found" : "Run unavailable"}</h1>
        <ErrorNotice error={summary.error} />
        <Link to="/runs/new" className={buttonVariants({ variant: "outline" })}>
          Start a new run
        </Link>
      </div>
    );
  }

  const run = summary.data;
  const finished = isTerminalStatus(run.status);
  const report = run.report ?? view.report;

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold">Research run</h1>
        <p>
          <span className="rounded border px-2 py-0.5 text-sm font-medium">
            Status: {STATUS_TEXT[run.status]}
          </span>
          {stream === "reconnecting" ? (
            <span className="text-muted-foreground ml-2 text-sm">
              Reconnecting to the live stream…
            </span>
          ) : null}
        </p>
        <p lang="tr" className="text-lg">
          {run.question}
        </p>
      </header>

      {/* Polite, throttled: step changes are announced calmly; approvals use their own alert. */}
      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      {view.pendingApproval ? (
        <ApprovalCard
          key={view.pendingApproval.approvalId}
          runId={runId}
          approval={view.pendingApproval}
          onSettled={refetchRun}
        />
      ) : null}

      <section aria-labelledby="progress-title" className="space-y-3">
        <h2 id="progress-title" className="text-lg font-semibold">
          Progress
        </h2>
        <Stepper steps={view.steps} />
        <BudgetMeter summary={run} />
      </section>

      {run.status === "SUCCEEDED" && report ? (
        <section aria-labelledby="report-title" className="space-y-3">
          <h2 id="report-title" className="text-lg font-semibold">
            Report
          </h2>
          <ReportPanel report={report} />
          <p className="text-muted-foreground text-sm">
            Total cost{" "}
            <span title={moneyTitle(run.cost.totalUsd)}>{formatMoney(run.cost.totalUsd)}</span>
            {" ("}
            <span title={moneyTitle(run.cost.paymentsUsdc)}>
              {formatMoney(run.cost.paymentsUsdc)} payments
            </span>
            {", "}
            <span title={moneyTitle(run.cost.llmUsd)}>{formatMoney(run.cost.llmUsd)} model</span>
            {")"}
          </p>
        </section>
      ) : null}

      {run.status === "FAILED" ? (
        <p role="alert" className="text-destructive font-medium">
          <span aria-hidden="true">{"⚠ "}</span>
          The run failed ({run.failureCode ?? view.failureCode ?? "unknown reason"}). Nothing more
          will be paid.
        </p>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>
            <h2>Payments</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <PaymentsPanel payments={view.payments} />
        </CardContent>
      </Card>

      <section aria-labelledby="timeline-title" className="space-y-3">
        <h2 id="timeline-title" className="text-lg font-semibold">
          Timeline
        </h2>
        <Timeline events={events} />
        {!finished && stream === "closed" ? (
          <p className="text-muted-foreground text-sm">The live stream ended; refreshing…</p>
        ) : null}
      </section>
    </div>
  );
}
