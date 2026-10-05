import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";

import { ErrorNotice } from "@/components/error-notice";
import { Button } from "@/components/ui/button";
import { startReconciliation } from "@/lib/api/queries";
import { ApiError } from "@/lib/api/source";
import type {
  ReconciliationCounters,
  ReconciliationReport,
  ReconciliationRunSummary,
} from "@/lib/api/types";
import { useNow } from "@/lib/hooks";
import {
  itemStatusText,
  mismatchText,
  PENDING_EXPLANATION,
  reconStatusText,
} from "@/lib/ledger-model";
import { formatMoney, moneyTitle } from "@/lib/money";
import { basescanTxUrl } from "@/lib/run-view-model";
import { isReplayMode } from "@/lib/mode";
import { useCapabilities } from "@/lib/use-capabilities";

const when = (iso: string) => new Date(iso).toLocaleString();

// ---- Run now ------------------------------------------------------------------------------

/** Shows a visual countdown; the screen-reader text is static so it is not read every second. */
function RetryCountdown({ retryAt, seconds }: { retryAt: number; seconds: number }) {
  const now = useNow(1000);
  const left = Math.max(0, Math.ceil((retryAt - now) / 1000));
  return (
    <>
      <p role="status" className="text-sm">
        <span aria-hidden="true">{"⏱ "}</span>
        The ledger limits how often reconciliation can start. You can run it again in about{" "}
        {seconds} seconds.
        <span aria-hidden="true"> ({left} s left)</span>
      </p>
    </>
  );
}

/** "Run now": starts a reconciliation and handles 409 (running), 429 (Retry-After) and 503. */
export function RunNowControl() {
  const { canOperate } = useCapabilities();
  const queryClient = useQueryClient();
  const [retry, setRetry] = useState<{ at: number; seconds: number } | null>(null);
  const [started, setStarted] = useState(false);
  const mutation = useMutation({
    mutationFn: startReconciliation,
    onMutate: () => {
      setStarted(false);
      setRetry(null);
    },
    onSuccess: () => {
      setStarted(true);
      void queryClient.invalidateQueries({ queryKey: ["reconciliation"] });
    },
    onError: (error) => {
      if (error instanceof ApiError && error.kind === "rate-limited") {
        const seconds = error.retryAfterSeconds ?? 30;
        setRetry({ at: Date.now() + seconds * 1000, seconds });
      }
      if (error instanceof ApiError && error.kind === "conflict") {
        void queryClient.invalidateQueries({ queryKey: ["reconciliation"] });
      }
    },
  });

  // Re-enable the button when the countdown is over (one timer, no per-second state here).
  useEffect(() => {
    if (retry === null) {
      return;
    }
    const id = setTimeout(
      () => {
        setRetry(null);
      },
      Math.max(0, retry.at - Date.now()),
    );
    return () => {
      clearTimeout(id);
    };
  }, [retry]);

  const error = mutation.error;
  const disabled = mutation.isPending || retry !== null;
  if (!canOperate) {
    return (
      <p className="text-muted-foreground text-sm">
        {isReplayMode
          ? "Disabled in the recorded demo: nothing can be started here."
          : "Read-only: running reconciliation needs an operator token."}
      </p>
    );
  }
  return (
    <div className="space-y-2">
      <Button
        disabled={disabled}
        aria-describedby="run-now-note"
        onClick={() => {
          mutation.mutate();
        }}
      >
        {mutation.isPending ? "Starting…" : "Run now"}
      </Button>
      <div id="run-now-note" className="space-y-1">
        {started ? (
          <p role="status">Reconciliation started. The report below updates itself.</p>
        ) : null}
        {retry !== null ? <RetryCountdown retryAt={retry.at} seconds={retry.seconds} /> : null}
        {error instanceof ApiError && error.kind === "conflict" ? (
          <p role="status" className="text-sm font-medium">
            <span aria-hidden="true">{"ℹ "}</span>A reconciliation is already running. Its report
            appears below when it finishes.
          </p>
        ) : error instanceof ApiError && error.kind === "rate-limited" ? null : error instanceof
            ApiError && error.kind === "not-ready" ? (
          <p role="alert" className="text-sm font-medium">
            <span aria-hidden="true">{"⚠ "}</span>The ledger is starting. Try again in a few
            seconds.
          </p>
        ) : error ? (
          <ErrorNotice error={error} />
        ) : null}
      </div>
    </div>
  );
}

// ---- report -------------------------------------------------------------------------------

function Counter({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-md border p-3">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="text-xl font-semibold">{value}</dd>
    </div>
  );
}

export function CounterCards({ counters }: { counters: ReconciliationCounters }) {
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      <Counter label="Checked" value={counters.checked} />
      <Counter label="Matched" value={counters.matched} />
      <Counter label="Pending" value={counters.pending} />
      <Counter label="Mismatches" value={counters.mismatches} />
      <Counter label="Used on chain" value={counters.resolvedUsed} />
      <Counter label="Unused on chain" value={counters.resolvedUnused} />
    </dl>
  );
}

export function ReportView({ report }: { report: ReconciliationReport }) {
  const hasPending = report.summary.pending > 0 || report.items.some((i) => i.status === "PENDING");
  return (
    <div className="space-y-6">
      <p className="text-sm">
        <span className="font-medium">{reconStatusText(report.status)}</span>
        {" · "}started <time dateTime={report.startedAt}>{when(report.startedAt)}</time>
        {report.finishedAt ? (
          <>
            {" · "}finished <time dateTime={report.finishedAt}>{when(report.finishedAt)}</time>
          </>
        ) : null}
        {" · "}
        {report.network}
        {report.safeBlock === null || report.safeBlock === undefined
          ? ""
          : ` · safe block ${String(report.safeBlock)}`}
      </p>
      <CounterCards counters={report.summary} />
      <p className="text-sm">
        Suspense account:{" "}
        <span title={moneyTitle(report.suspense)}>{formatMoney(report.suspense)}</span>
        {report.suspenseSide ? ` (${report.suspenseSide.toLowerCase()})` : ""}. Differences between
        the books and the chain are parked here until a person clears them.
      </p>
      {hasPending ? <p className="text-muted-foreground text-sm">{PENDING_EXPLANATION}</p> : null}
      {report.items.length === 0 ? (
        <p className="text-muted-foreground text-sm">
          {report.status === "RUNNING"
            ? "The run is in progress. This report updates itself when it finishes."
            : "This run checked no payments."}
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Payments checked by this reconciliation run</caption>
            <thead>
              <tr>
                {["Payment", "Status", "Amount", "Payer", "Finding"].map((heading) => (
                  <th key={heading} scope="col" className="py-2 pr-4 font-semibold">
                    {heading}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {report.items.map((item) => {
                const url = item.txHash ? basescanTxUrl(item.txHash) : null;
                return (
                  <tr key={item.paymentId} className="border-t align-top">
                    <th scope="row" className="py-2 pr-4 font-normal">
                      <Link
                        to="/ledger/payments/$paymentId"
                        params={{ paymentId: item.paymentId }}
                        className="underline underline-offset-4"
                        title={item.paymentId}
                      >
                        {item.paymentId.slice(0, 8)}
                        <span className="sr-only"> payment details</span>
                      </Link>
                    </th>
                    <td className="py-2 pr-4 whitespace-nowrap">
                      <span aria-hidden="true">
                        {item.status === "MATCHED"
                          ? "✓ "
                          : item.status === "PENDING"
                            ? "⏳ "
                            : "⚠ "}
                      </span>
                      {itemStatusText(item.status)}
                    </td>
                    <td className="py-2 pr-4 whitespace-nowrap" title={moneyTitle(item.amount)}>
                      {formatMoney(item.amount)}
                    </td>
                    <td className="py-2 pr-4 font-mono text-xs break-all">{item.payer}</td>
                    <td className="py-2 pr-4">
                      {item.mismatch ? (
                        <>
                          <span className="font-medium">{item.mismatch.kind}</span>
                          <span className="block">{mismatchText(item.mismatch.kind)}</span>
                        </>
                      ) : item.status === "PENDING" ? (
                        "Waiting for Base Sepolia's safe block"
                      ) : (
                        "—"
                      )}
                      {url ? (
                        <a
                          className="block underline underline-offset-4"
                          href={url}
                          target="_blank"
                          rel="noopener noreferrer"
                        >
                          View on Basescan (Sepolia, new tab)
                        </a>
                      ) : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function RunHistory({ runs }: { runs: readonly ReconciliationRunSummary[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Reconciliation runs, newest first</caption>
        <thead>
          <tr>
            {["Started", "Status", "Checked", "Mismatches", "Pending"].map((heading) => (
              <th key={heading} scope="col" className="py-2 pr-4 font-semibold">
                {heading}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <tr key={run.runId} className="border-t">
              <th scope="row" className="py-2 pr-4 font-normal whitespace-nowrap">
                <Link
                  to="/reconciliation/$reconRunId"
                  params={{ reconRunId: run.runId }}
                  className="underline underline-offset-4"
                >
                  <time dateTime={run.startedAt}>{when(run.startedAt)}</time>
                </Link>
              </th>
              <td className="py-2 pr-4">{run.status}</td>
              <td className="py-2 pr-4">{run.summary.checked}</td>
              <td className="py-2 pr-4">{run.summary.mismatches}</td>
              <td className="py-2 pr-4">{run.summary.pending}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
