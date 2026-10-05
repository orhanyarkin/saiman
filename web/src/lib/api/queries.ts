/**
 * `queryOptions` factories and query keys (docs/design/m5-dashboard.md). T3c adds
 * `['ledger', …]` and `['reconciliation', …]` here. Response types come from the generated
 * OpenAPI schemas via `types.ts`.
 */
import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";

import { fetchPing } from "@/lib/api/ping";
import { parseRunEvents } from "@/lib/api/run-events";
import { apiGet, apiPost } from "@/lib/api/source";
import type {
  ApprovalResponse,
  ApprovalView,
  DecisionRequest,
  RunPage,
  RunPayments,
  RunSummary,
  SpendOverview,
  StartRunRequest,
  StartRunResponse,
} from "@/lib/api/types";
import { isTerminalStatus } from "@/lib/api/types";

export const queryKeys = {
  ping: ["ping"] as const,
  runs: ["runs"] as const,
  runList: (limit: number) => ["runs", "list", limit] as const,
  runRecent: ["runs", "recent"] as const,
  run: (runId: string) => ["runs", runId] as const,
  runSummary: (runId: string) => ["runs", runId, "summary"] as const,
  runEvents: (runId: string) => ["runs", runId, "events"] as const,
  runPayments: (runId: string) => ["runs", runId, "payments"] as const,
  approvals: ["approvals"] as const,
  approvalsPending: ["approvals", "pending"] as const,
  spendAll: ["spend"] as const,
  spend: (day: string) => ["spend", day] as const,
};

export const pingQuery = () => queryOptions({ queryKey: queryKeys.ping, queryFn: fetchPing });

const SUMMARY_POLL_MS = 3000;
const PAYMENTS_POLL_MS = 10_000;
export const APPROVALS_POLL_MS = 5000;
export const RUNS_PAGE_SIZE = 20;

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

/**
 * Current payment intents of a run. Event-driven invalidation (PAYMENT_*) keeps it fresh; the poll
 * covers what emits no event: while the run is live, or while any intent is HELD (the chain
 * resolver settles or releases it silently), it refetches every 10 s.
 */
export const runPaymentsQuery = (runId: string, runTerminal: boolean) =>
  queryOptions({
    queryKey: queryKeys.runPayments(runId),
    queryFn: ({ signal }) =>
      apiGet<RunPayments>(`/api/v1/runs/${encodeURIComponent(runId)}/payments`, signal),
    refetchInterval: (query) => {
      const held = query.state.data?.items.some((item) => item.status === "HELD") ?? false;
      return !runTerminal || held ? PAYMENTS_POLL_MS : false;
    },
  });

/** Keyset-paged run list: each page's `next` is the opaque `before` cursor of the following one. */
export const runListQuery = (limit = RUNS_PAGE_SIZE) =>
  infiniteQueryOptions({
    queryKey: queryKeys.runList(limit),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ limit: String(limit) });
      if (pageParam !== null) {
        params.set("before", pageParam);
      }
      return apiGet<RunPage>(`/api/v1/runs?${params.toString()}`, signal);
    },
    getNextPageParam: (last) => last.next ?? null,
  });

/** The landing page's "recent runs": just the first page, five items. */
export const recentRunsQuery = () =>
  queryOptions({
    queryKey: queryKeys.runRecent,
    queryFn: ({ signal }) => apiGet<RunPage>("/api/v1/runs?limit=5", signal),
  });

/** Pending approvals across all runs, polled so the nav badge stays current. */
export const pendingApprovalsQuery = () =>
  queryOptions({
    queryKey: queryKeys.approvalsPending,
    queryFn: ({ signal }) => apiGet<ApprovalView[]>("/api/v1/approvals?status=PENDING", signal),
    refetchInterval: APPROVALS_POLL_MS,
  });

/** Spend overview of one UTC day (`YYYY-MM-DD`): daily cap, usage, limits, per-tool totals. */
export const spendQuery = (day: string) =>
  queryOptions({
    queryKey: queryKeys.spend(day),
    queryFn: ({ signal }) =>
      apiGet<SpendOverview>(`/api/v1/spend?day=${encodeURIComponent(day)}`, signal),
  });

export function startRun(request: StartRunRequest): Promise<StartRunResponse> {
  return apiPost<StartRunResponse>("/api/v1/runs", request);
}

export function decideApproval(
  runId: string,
  approvalId: string,
  decision: NonNullable<DecisionRequest["decision"]>,
): Promise<ApprovalResponse> {
  return apiPost<ApprovalResponse>(
    `/api/v1/runs/${encodeURIComponent(runId)}/approvals/${encodeURIComponent(approvalId)}`,
    { decision } satisfies DecisionRequest,
  );
}
