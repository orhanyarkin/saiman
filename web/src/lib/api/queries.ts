/**
 * `queryOptions` factories and query keys (docs/design/m5-dashboard.md). T3c adds
 * `['ledger', …]` and `['reconciliation', …]` here. Response types come from the generated
 * OpenAPI schemas via `types.ts`.
 */
import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";

import { fetchPing } from "@/lib/api/ping";
import { parseRunEvents } from "@/lib/api/run-events";
import { apiGet, apiPost } from "@/lib/api/source";
import { LEDGER_POLL_MS, ledgerPollPhase } from "@/lib/ledger-model";
import type {
  ApprovalResponse,
  ApprovalView,
  DecisionRequest,
  LedgerBook,
  PaymentDetail,
  PaymentPage,
  Me,
  ReconciliationReport,
  ReconciliationRunList,
  ReconciliationStarted,
  RevenueReport,
  TrialBalanceRow,
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
  me: ["me"] as const,
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

/**
 * Who the token is (ADR-0023). Not retried and kept for a minute: a failure (401, no endpoint on an
 * older backend, network) means "unknown", which the UI treats as a reader.
 */
export const meQuery = () =>
  queryOptions({
    queryKey: queryKeys.me,
    queryFn: ({ signal }) => apiGet<Me>("/api/v1/me", signal),
    retry: false,
    staleTime: 60_000,
  });

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

// ---- ledger service (docs/api/ledger.openapi.json) ----------------------------------------

export const LEDGER_PAGE_SIZE = 20;
const RECON_POLL_MS = 2000;

export const ledgerKeys = {
  all: ["ledger"] as const,
  trialBalance: ["ledger", "trial-balance"] as const,
  payments: (filter: { runId: string | null; book: LedgerBook | null }) =>
    ["ledger", "payments", filter] as const,
  runPayments: (runId: string) => ["ledger", "run-payments", runId] as const,
  payment: (id: string) => ["ledger", "payment", id] as const,
  revenue: (payTo: string | null) => ["ledger", "revenue", payTo] as const,
  reconRuns: ["reconciliation", "runs"] as const,
  reconRun: (id: string) => ["reconciliation", "run", id] as const,
};

export const trialBalanceQuery = () =>
  queryOptions({
    queryKey: ledgerKeys.trialBalance,
    queryFn: ({ signal }) => apiGet<TrialBalanceRow[]>("/api/v1/ledger/trial-balance", signal),
  });

function paymentsUrl(
  filter: { runId?: string | null; book?: LedgerBook | null },
  limit: number,
  before: string | null,
): string {
  // Fixed parameter order keeps the URL stable (and fixtures keyable).
  const params = new URLSearchParams();
  if (filter.runId) {
    params.set("runId", filter.runId);
  }
  if (filter.book) {
    params.set("book", filter.book);
  }
  params.set("limit", String(limit));
  if (before !== null) {
    params.set("before", before);
  }
  return `/api/v1/ledger/payments?${params.toString()}`;
}

/** Keyset-paged payments, optionally of one run and/or one book. */
export const ledgerPaymentsQuery = (
  filter: { runId?: string | null; book?: LedgerBook | null } = {},
) =>
  infiniteQueryOptions({
    queryKey: ledgerKeys.payments({ runId: filter.runId ?? null, book: filter.book ?? null }),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) =>
      apiGet<PaymentPage>(paymentsUrl(filter, LEDGER_PAGE_SIZE, pageParam), signal),
    getNextPageParam: (last) => last.nextCursor ?? null,
  });

/** The run page's panel: one page of the run's payments, polled by the caller while armed. */
export const runLedgerPaymentsQuery = (runId: string, armedAt: number) =>
  queryOptions({
    queryKey: ledgerKeys.runPayments(runId),
    queryFn: ({ signal }) => apiGet<PaymentPage>(paymentsUrl({ runId }, 50, null), signal),
    // Evaluated after every fetch (and when `armedAt` changes): polling ends by itself.
    refetchInterval: (query) =>
      ledgerPollPhase(armedAt, Date.now(), query.state.data?.items ?? []) === "polling"
        ? LEDGER_POLL_MS
        : false,
  });

export const paymentDetailQuery = (paymentId: string) =>
  queryOptions({
    queryKey: ledgerKeys.payment(paymentId),
    queryFn: ({ signal }) =>
      apiGet<PaymentDetail>(`/api/v1/ledger/payments/${encodeURIComponent(paymentId)}`, signal),
  });

export const revenueQuery = (payTo: string | null) =>
  queryOptions({
    queryKey: ledgerKeys.revenue(payTo),
    queryFn: ({ signal }) =>
      apiGet<RevenueReport>(
        payTo === null
          ? "/api/v1/ledger/revenue"
          : `/api/v1/ledger/revenue?payTo=${encodeURIComponent(payTo)}`,
        signal,
      ),
  });

export const reconRunsQuery = (limit = 20) =>
  queryOptions({
    queryKey: ledgerKeys.reconRuns,
    queryFn: ({ signal }) =>
      apiGet<ReconciliationRunList>(`/api/v1/reconciliation/runs?limit=${String(limit)}`, signal),
  });

/** One report by id or `latest`; polled while the run is RUNNING (PARTIAL/FAILED/COMPLETED are final). */
export const reconRunQuery = (id: string) =>
  queryOptions({
    queryKey: ledgerKeys.reconRun(id),
    queryFn: ({ signal }) =>
      apiGet<ReconciliationReport>(`/api/v1/reconciliation/runs/${encodeURIComponent(id)}`, signal),
    refetchInterval: (query) => (query.state.data?.status === "RUNNING" ? RECON_POLL_MS : false),
  });

export function startReconciliation(): Promise<ReconciliationStarted> {
  return apiPost<ReconciliationStarted>("/api/v1/reconciliation/runs", {});
}

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
