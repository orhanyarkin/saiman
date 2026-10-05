/**
 * Pure reducers from the ordered event list to what the run page shows. No React, no fetching,
 * no money formatting except for the timeline labels; fully unit-tested.
 */
import { formatMoney } from "@/lib/money";
import {
  type Money,
  type Report,
  type RunCost,
  type RunEvent,
  type RunEventOf,
  type RunStep,
} from "@/lib/api/run-events";

export type StepStatus = "pending" | "running" | "done";

export type ApprovalRequest = RunEventOf<"PAYMENT_APPROVAL_REQUIRED">["data"];

export type PaymentState =
  "awaiting-approval" | "approved" | "rejected" | "expired" | "settled" | "denied" | "ambiguous";

export interface PaymentRow {
  key: string;
  state: PaymentState;
  amount: Money;
  /** Present for settled payments only. */
  txHash?: string;
  /** Present for denied payments only. */
  reason?: string;
  payTo?: string;
  resource?: string;
}

export interface RunView {
  question: string | null;
  budget: Money | null;
  steps: Record<RunStep, StepStatus>;
  currentStep: RunStep | null;
  /** Latest PAYMENT_APPROVAL_REQUIRED without a DECIDED for the same approval, on a live run. */
  pendingApproval: ApprovalRequest | null;
  payments: PaymentRow[];
  report: Report | null;
  cost: RunCost | null;
  failureCode: string | null;
  terminal: boolean;
}

export function deriveRunView(events: readonly RunEvent[]): RunView {
  const steps: Record<RunStep, StepStatus> = {
    PLANNER: "pending",
    RESEARCHER: "pending",
    RISK: "pending",
    SYNTHESIS: "pending",
  };
  const view: RunView = {
    question: null,
    budget: null,
    steps,
    currentStep: null,
    pendingApproval: null,
    payments: [],
    report: null,
    cost: null,
    failureCode: null,
    terminal: false,
  };
  const decided = new Set<string>();
  const approvals: ApprovalRequest[] = [];
  const rowByIntent = new Map<string, PaymentRow>();

  for (const event of events) {
    switch (event.type) {
      case "RUN_STARTED":
        view.question = event.data.question;
        view.budget = event.data.budget;
        break;
      case "STEP_STARTED":
        steps[event.data.step] = "running";
        view.currentStep = event.data.step;
        break;
      case "STEP_COMPLETED":
        steps[event.data.step] = "done";
        if (view.currentStep === event.data.step) {
          view.currentStep = null;
        }
        break;
      case "PAYMENT_APPROVAL_REQUIRED": {
        approvals.push(event.data);
        const row: PaymentRow = {
          key: event.data.paymentIntentId,
          state: "awaiting-approval",
          amount: event.data.amount,
          payTo: event.data.payTo,
          resource: event.data.resource,
        };
        rowByIntent.set(event.data.paymentIntentId, row);
        view.payments.push(row);
        break;
      }
      case "PAYMENT_APPROVAL_DECIDED": {
        decided.add(event.data.approvalId);
        const approval = approvals.find((a) => a.approvalId === event.data.approvalId);
        const row = approval ? rowByIntent.get(approval.paymentIntentId) : undefined;
        if (row) {
          row.state =
            event.data.decision === "APPROVED"
              ? "approved"
              : event.data.decision === "REJECTED"
                ? "rejected"
                : "expired";
        }
        break;
      }
      case "PAYMENT_SETTLED": {
        const row = rowByIntent.get(event.data.paymentIntentId);
        if (row) {
          row.state = "settled";
          row.txHash = event.data.txHash;
        } else {
          const created: PaymentRow = {
            key: event.data.paymentIntentId,
            state: "settled",
            amount: event.data.amount,
            txHash: event.data.txHash,
          };
          rowByIntent.set(created.key, created);
          view.payments.push(created);
        }
        break;
      }
      case "PAYMENT_AMBIGUOUS": {
        const row = rowByIntent.get(event.data.paymentIntentId);
        if (row) {
          row.state = "ambiguous";
        } else {
          const created: PaymentRow = {
            key: event.data.paymentIntentId,
            state: "ambiguous",
            amount: event.data.amount,
          };
          rowByIntent.set(created.key, created);
          view.payments.push(created);
        }
        break;
      }
      case "PAYMENT_DENIED":
        view.payments.push({
          key: `denied:${String(event.seq)}`,
          state: "denied",
          amount: event.data.amount,
          reason: event.data.reason,
        });
        break;
      case "RUN_COMPLETED":
        view.terminal = true;
        view.report = event.data.report;
        view.cost = event.data.cost;
        break;
      case "RUN_FAILED":
        view.terminal = true;
        view.failureCode = event.data.failureCode;
        view.cost = event.data.costSoFar;
        break;
      default:
        break;
    }
  }

  if (!view.terminal) {
    const latest = approvals.at(-1);
    view.pendingApproval = latest && !decided.has(latest.approvalId) ? latest : null;
  }
  return view;
}

export function stepLabel(step: RunStep): string {
  return {
    PLANNER: "Planner",
    RESEARCHER: "Researcher",
    RISK: "Risk check",
    SYNTHESIS: "Synthesis",
  }[step];
}

const REASON_TEXT: Record<string, string> = {
  RUN_BUDGET: "run budget would be exceeded",
  DAILY_CAP: "daily cap reached",
  PAYEE_NOT_ALLOWED: "payee is not on the allowlist",
  OVER_PER_REQUEST_MAX: "above the per-request maximum",
  UNKNOWN_INTENT: "unknown payment intent",
  APPROVAL_REJECTED: "you rejected the payment",
  APPROVAL_EXPIRED: "approval expired",
  APPROVAL_MISMATCH: "the seller's price changed after approval",
  OFFER_NOT_PAYABLE: "offer cannot be paid (unsupported network or asset)",
  MAX_PAID_CALLS: "too many paid calls in this run",
  HOURLY_PAID_CALLS: "hourly paid-call limit reached",
  INVALID_ARGS: "invalid tool arguments",
};

export function denialText(reason: string): string {
  return REASON_TEXT[reason] ?? reason;
}

export interface TimelineItem {
  seq: number;
  at: string;
  label: string;
  detail: string | null;
}

/** One human line per event for the timeline (tool, 402/approval, payment, model, tokens, USD). */
export function describeEvent(event: RunEvent): TimelineItem {
  const base = { seq: event.seq, at: event.occurredAt };
  switch (event.type) {
    case "RUN_STARTED":
      return { ...base, label: "Run started", detail: `Budget ${formatMoney(event.data.budget)}` };
    case "STEP_STARTED":
      return { ...base, label: `${stepLabel(event.data.step)} started`, detail: null };
    case "STEP_COMPLETED":
      return { ...base, label: `${stepLabel(event.data.step)} finished`, detail: null };
    case "PLAN_CREATED":
      return {
        ...base,
        label: "Plan created",
        detail: `Tickers: ${event.data.tickers.join(", ")}`,
      };
    case "TOOL_CALL_REQUESTED":
      return { ...base, label: `Tool call: ${event.data.tool}`, detail: event.data.arguments };
    case "PAYMENT_APPROVAL_REQUIRED":
      return {
        ...base,
        label: "Payment needs your approval (HTTP 402)",
        detail: `${formatMoney(event.data.amount)} to ${event.data.payTo}`,
      };
    case "PAYMENT_APPROVAL_DECIDED":
      return { ...base, label: `Approval ${event.data.decision.toLowerCase()}`, detail: null };
    case "PAYMENT_DENIED":
      return {
        ...base,
        label: "Payment denied",
        detail: `${denialText(event.data.reason)} (${formatMoney(event.data.amount)})`,
      };
    case "PAYMENT_SETTLED":
      return { ...base, label: "Payment settled", detail: formatMoney(event.data.amount) };
    case "PAYMENT_AMBIGUOUS":
      return {
        ...base,
        label: "Payment outcome unknown",
        detail: `${formatMoney(event.data.amount)} stays reserved until the chain confirms`,
      };
    case "TOOL_CALL_COMPLETED":
      return {
        ...base,
        label: `Tool finished: ${event.data.tool}`,
        detail: `${event.data.paid ? "paid" : "free"}, ${String(event.data.citationCount)} citations`,
      };
    case "MODEL_CALL_COMPLETED":
      return {
        ...base,
        label: `Model call (${stepLabel(event.data.step)})`,
        detail: `${event.data.model}, ${event.data.tier}, ${String(event.data.inputTokens)} in / ${String(event.data.outputTokens)} out tokens, ${formatMoney(event.data.costUsd)}`,
      };
    case "RUN_COMPLETED":
      return {
        ...base,
        label: "Run completed",
        detail: `Total ${formatMoney(event.data.cost.totalUsd)}`,
      };
    case "RUN_FAILED":
      return { ...base, label: "Run failed", detail: event.data.failureCode };
  }
}

/** A citation becomes a link only for real KAP disclosure URLs; everything else stays text. */
export function safeKapUrl(sourceUrl: string): string | null {
  if (!sourceUrl.startsWith("https://www.kap.org.tr/")) {
    return null;
  }
  try {
    const url = new URL(sourceUrl);
    return url.protocol === "https:" &&
      url.origin === "https://www.kap.org.tr" &&
      url.username === ""
      ? url.href
      : null;
  } catch {
    return null;
  }
}

export function basescanTxUrl(txHash: string): string | null {
  return /^0x[0-9a-fA-F]{64}$/.test(txHash) ? `https://sepolia.basescan.org/tx/${txHash}` : null;
}
