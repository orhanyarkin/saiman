/**
 * `queryOptions` factories and query keys (docs/design/m5-dashboard.md). T3b/T3c add `['approvals',
 * 'pending']`, `['spend', day]`, `['ledger', …]` and `['reconciliation', …]` here.
 */
import { queryOptions } from "@tanstack/react-query";

import { fetchPing } from "@/lib/api/ping";
import { parseRunEvents } from "@/lib/api/run-events";
import { apiGet, apiPost } from "@/lib/api/source";
import type {
  ApprovalResponse,
  DecisionRequest,
  RunSummary,
  StartRunRequest,
  StartRunResponse,
} from "@/lib/api/types";
import { isTerminalStatus } from "@/lib/api/types";

export const queryKeys = {
  ping: ["ping"] as const,
  runs: ["runs"] as const,
  run: (runId: string) => ["runs", runId] as const,
  runSummary: (runId: string) => ["runs", runId, "summary"] as const,
  runEvents: (runId: string) => ["runs", runId, "events"] as const,
};

export const pingQuery = () => queryOptions({ queryKey: queryKeys.ping, queryFn: fetchPing });

const SUMMARY_POLL_MS = 3000;

export const runSummaryQuery = (runId: string) =>
  queryOptions({
    queryKey: queryKeys.runSummary(runId),
    queryFn: ({ signal }) =>
      apiGet<RunSummary>(`/api/v1/runs/${encodeURIComponent(runId)}`, signal),
    // A safety net next to the event-driven invalidation; stops once the run is over.
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status !== undefined && isTerminalStatus(status) ? false : SUMMARY_POLL_MS;
    },
  });

/** The ordered JSON export of a finished run (`Accept: application/json`), immutable once read. */
export const runEventsExportQuery = (runId: string) =>
  queryOptions({
    queryKey: queryKeys.runEvents(runId),
    queryFn: async ({ signal }) =>
      parseRunEvents(
        await apiGet<unknown>(`/api/v1/runs/${encodeURIComponent(runId)}/events`, signal),
      ),
    staleTime: Infinity,
  });

export function startRun(request: StartRunRequest): Promise<StartRunResponse> {
  return apiPost<StartRunResponse>("/api/v1/runs", request);
}

export function decideApproval(
  runId: string,
  approvalId: string,
  decision: DecisionRequest["decision"],
): Promise<ApprovalResponse> {
  return apiPost<ApprovalResponse>(
    `/api/v1/runs/${encodeURIComponent(runId)}/approvals/${encodeURIComponent(approvalId)}`,
    { decision } satisfies DecisionRequest,
  );
}
