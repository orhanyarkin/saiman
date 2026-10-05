/**
 * Hand-written response types for the orchestrator endpoints that exist today
 * (`RunController`, `ApprovalController`) and the pending list endpoints.
 *
 * This is the ONE place to swap when `docs/api/*.openapi.json` exist: T3b/T3c replace these
 * declarations with re-exports from the `openapi-typescript` output
 * (`src/lib/api/generated/*.ts`). Callers import types from here only.
 */
import type { Money, Report, RunCost } from "@/lib/api/run-events";

export type { Money, Report, RunCost };

export type RunStatus = "QUEUED" | "RUNNING" | "AWAITING_APPROVAL" | "SUCCEEDED" | "FAILED";

export function isTerminalStatus(status: RunStatus): boolean {
  return status === "SUCCEEDED" || status === "FAILED";
}

/** `GET /api/v1/ping` */
export interface PingResponse {
  service: string;
  dbTime: string;
  traceId: string | null;
}

/** `POST /api/v1/runs` request. `budgetAtomic` is USDC atomic units. */
export interface StartRunRequest {
  question: string;
  budgetAtomic?: number;
}

/** `POST /api/v1/runs` 202 body. */
export interface StartRunResponse {
  runId: string;
  eventsUrl: string;
  traceId: string | null;
}

/** `GET /api/v1/runs/{id}` */
export interface RunSummary {
  runId: string;
  status: RunStatus;
  question: string;
  budget: Money;
  reserved: Money;
  committed: Money;
  cost: RunCost;
  failureCode: string | null;
  traceId: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  report: Report | null;
}

/** `POST /api/v1/runs/{runId}/approvals/{approvalId}` request. */
export interface DecisionRequest {
  decision: "APPROVE" | "REJECT";
}

/** `POST /api/v1/runs/{runId}/approvals/{approvalId}` 200 body. */
export interface ApprovalResponse {
  approvalId: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
}
