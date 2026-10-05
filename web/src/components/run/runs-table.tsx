import { Link } from "@tanstack/react-router";

import { AnnotationCallout } from "@/components/replay/run-annotation";
import { runAnnotation } from "@/lib/run-annotation";
import { StatusBadge } from "@/components/run/status-badge";
import type { RunListItem } from "@/lib/api/types";
import { formatMoney, moneyTitle } from "@/lib/money";

function Cost({ run }: { run: RunListItem }) {
  const { paymentsUsdc, llmUsd } = run.cost;
  return (
    <>
      <span title={moneyTitle(paymentsUsdc)}>{formatMoney(paymentsUsdc)}</span>
      {" + "}
      <span title={moneyTitle(llmUsd)}>{formatMoney(llmUsd)}</span>
    </>
  );
}

function BudgetUsed({ run }: { run: RunListItem }) {
  const { budget, committed } = run;
  const usable = Number.isSafeInteger(budget.atomicUnits) && budget.atomicUnits > 0;
  const used = Number.isSafeInteger(committed.atomicUnits) ? committed.atomicUnits : 0;
  return (
    <div className="space-y-0.5">
      <span title={moneyTitle(budget)}>
        {formatMoney(committed)} of {formatMoney(budget)}
      </span>
      {usable ? (
        <meter
          className="block h-2 w-24"
          min={0}
          max={budget.atomicUnits}
          value={Math.min(used, budget.atomicUnits)}
          aria-label="Budget used"
        />
      ) : null}
    </div>
  );
}

/** Runs as a data table: question, status, cost (payments + model), budget used, created. */
export function RunsTable({ runs, caption }: { runs: readonly RunListItem[]; caption: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {["Question", "Status", "Cost (payments + model)", "Budget used", "Created"].map(
              (heading) => (
                <th key={heading} scope="col" className="py-2 pr-4 font-semibold">
                  {heading}
                </th>
              ),
            )}
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <RunRow key={run.runId} run={run} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function RunRow({ run }: { run: RunListItem }) {
  const annotation = runAnnotation(run.runId);
  return (
    <tr className="border-t align-top">
      <th scope="row" className="max-w-xs py-2 pr-4 font-normal">
        <Link
          to="/runs/$runId"
          params={{ runId: run.runId }}
          lang="tr"
          title={run.question}
          className="line-clamp-2 underline underline-offset-4"
        >
          {run.question}
        </Link>
      </th>
      <td className="py-2 pr-4">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={run.status} />
          {run.pendingApprovals > 0 ? (
            <span className="rounded border border-amber-600 px-2 py-0.5 text-sm font-medium whitespace-nowrap">
              <span aria-hidden="true">{"⚠ "}</span>
              {run.pendingApprovals} approval{run.pendingApprovals === 1 ? "" : "s"} pending
            </span>
          ) : null}
        </div>
        {annotation ? (
          <div className="mt-2 max-w-xs">
            <AnnotationCallout annotation={annotation} compact />
          </div>
        ) : null}
      </td>
      <td className="py-2 pr-4 whitespace-nowrap">
        <Cost run={run} />
      </td>
      <td className="py-2 pr-4">
        <BudgetUsed run={run} />
      </td>
      <td className="py-2 whitespace-nowrap">
        <time dateTime={run.createdAt}>{new Date(run.createdAt).toLocaleString()}</time>
      </td>
    </tr>
  );
}
