/**
 * `agent.run-step.v1` (docs/events/agent.run-step.v1.md): the envelope and the 14 event types,
 * hand written, with a runtime guard. The guard is the trust boundary for everything that arrives
 * over SSE or the JSON export: an unknown type or a wrong shape is dropped, never rendered.
 * `run-events.test.ts` parses the shared golden fixtures, so the Java contract and this file cannot
 * drift apart silently.
 */
import type { MoneyLike } from "@/lib/money";

export type Money = MoneyLike;

export const RUN_STEPS = ["PLANNER", "RESEARCHER", "RISK", "SYNTHESIS"] as const;
export type RunStep = (typeof RUN_STEPS)[number];

export const DENIAL_REASONS = [
  "RUN_BUDGET",
  "DAILY_CAP",
  "PAYEE_NOT_ALLOWED",
  "OVER_PER_REQUEST_MAX",
  "UNKNOWN_INTENT",
  "APPROVAL_REJECTED",
  "APPROVAL_EXPIRED",
  "APPROVAL_MISMATCH",
  "OFFER_NOT_PAYABLE",
  "MAX_PAID_CALLS",
  "HOURLY_PAID_CALLS",
  "INVALID_ARGS",
] as const;
export type DenialReason = (typeof DENIAL_REASONS)[number];

export type ApprovalDecision = "APPROVED" | "REJECTED" | "EXPIRED";

export interface Citation {
  chunkId: string;
  sourceUrl: string;
  title: string;
}

export interface Report {
  answer: string;
  citations: Citation[];
}

export interface RunCost {
  paymentsUsdc: Money;
  llmUsd: Money;
  totalUsd: Money;
}

interface Base<T extends string, D> {
  eventId: string;
  runId: string;
  seq: number;
  type: T;
  occurredAt: string;
  data: D;
}

export type RunEvent =
  | Base<"RUN_STARTED", { question: string; budget: Money }>
  | Base<"STEP_STARTED", { step: RunStep }>
  | Base<"STEP_COMPLETED", { step: RunStep }>
  | Base<"PLAN_CREATED", { tickers: string[]; tasks: string[] }>
  | Base<"TOOL_CALL_REQUESTED", { tool: string; arguments: string }>
  | Base<
      "PAYMENT_APPROVAL_REQUIRED",
      {
        approvalId: string;
        paymentIntentId: string;
        amount: Money;
        payTo: string;
        resource: string;
        expiresAt: string;
      }
    >
  | Base<"PAYMENT_APPROVAL_DECIDED", { approvalId: string; decision: ApprovalDecision }>
  | Base<"PAYMENT_DENIED", { reason: DenialReason; amount: Money }>
  | Base<"PAYMENT_SETTLED", { paymentIntentId: string; amount: Money; txHash: string }>
  | Base<"PAYMENT_AMBIGUOUS", { paymentIntentId: string; amount: Money }>
  | Base<"TOOL_CALL_COMPLETED", { tool: string; paid: boolean; citationCount: number }>
  | Base<
      "MODEL_CALL_COMPLETED",
      {
        step: RunStep;
        tier: string;
        model: string;
        inputTokens: number;
        outputTokens: number;
        costUsd: Money;
      }
    >
  | Base<"RUN_COMPLETED", { report: Report; cost: RunCost }>
  | Base<"RUN_FAILED", { failureCode: string; costSoFar: RunCost }>;

export type RunEventType = RunEvent["type"];
export type RunEventOf<T extends RunEventType> = Extract<RunEvent, { type: T }>;

export const RUN_EVENT_TYPES: readonly RunEventType[] = [
  "RUN_STARTED",
  "STEP_STARTED",
  "STEP_COMPLETED",
  "PLAN_CREATED",
  "TOOL_CALL_REQUESTED",
  "PAYMENT_APPROVAL_REQUIRED",
  "PAYMENT_APPROVAL_DECIDED",
  "PAYMENT_DENIED",
  "PAYMENT_SETTLED",
  "PAYMENT_AMBIGUOUS",
  "TOOL_CALL_COMPLETED",
  "MODEL_CALL_COMPLETED",
  "RUN_COMPLETED",
  "RUN_FAILED",
];

export function isTerminalEvent(event: RunEvent): boolean {
  return event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED";
}

// --- guard -----------------------------------------------------------------------------------

type Json = Record<string, unknown>;
type Check = (value: unknown) => boolean;

const isObject = (v: unknown): v is Json =>
  typeof v === "object" && v !== null && !Array.isArray(v);
const isString: Check = (v) => typeof v === "string";
const isNonNegInt: Check = (v) => typeof v === "number" && Number.isInteger(v) && v >= 0;
const isBool: Check = (v) => typeof v === "boolean";
const isStringArray: Check = (v) => Array.isArray(v) && v.every(isString);
const oneOf =
  (allowed: readonly string[]): Check =>
  (v) =>
    typeof v === "string" && allowed.includes(v);
const isStep = oneOf(RUN_STEPS);

const isMoney: Check = (v) =>
  isObject(v) &&
  typeof v.atomicUnits === "number" &&
  Number.isInteger(v.atomicUnits) &&
  isString(v.asset) &&
  isNonNegInt(v.decimals);

const isCost: Check = (v) =>
  isObject(v) && isMoney(v.paymentsUsdc) && isMoney(v.llmUsd) && isMoney(v.totalUsd);

const isCitation: Check = (v) =>
  isObject(v) && isString(v.chunkId) && isString(v.sourceUrl) && isString(v.title);

const isReport: Check = (v) =>
  isObject(v) && isString(v.answer) && Array.isArray(v.citations) && v.citations.every(isCitation);

const shapes: Record<RunEventType, Record<string, Check>> = {
  RUN_STARTED: { question: isString, budget: isMoney },
  STEP_STARTED: { step: isStep },
  STEP_COMPLETED: { step: isStep },
  PLAN_CREATED: { tickers: isStringArray, tasks: isStringArray },
  TOOL_CALL_REQUESTED: { tool: isString, arguments: isString },
  PAYMENT_APPROVAL_REQUIRED: {
    approvalId: isString,
    paymentIntentId: isString,
    amount: isMoney,
    payTo: isString,
    resource: isString,
    expiresAt: isString,
  },
  PAYMENT_APPROVAL_DECIDED: {
    approvalId: isString,
    decision: oneOf(["APPROVED", "REJECTED", "EXPIRED"]),
  },
  PAYMENT_DENIED: { reason: oneOf(DENIAL_REASONS), amount: isMoney },
  PAYMENT_SETTLED: { paymentIntentId: isString, amount: isMoney, txHash: isString },
  PAYMENT_AMBIGUOUS: { paymentIntentId: isString, amount: isMoney },
  TOOL_CALL_COMPLETED: { tool: isString, paid: isBool, citationCount: isNonNegInt },
  MODEL_CALL_COMPLETED: {
    step: isStep,
    tier: isString,
    model: isString,
    inputTokens: isNonNegInt,
    outputTokens: isNonNegInt,
    costUsd: isMoney,
  },
  RUN_COMPLETED: { report: isReport, cost: isCost },
  RUN_FAILED: { failureCode: isString, costSoFar: isCost },
};

/**
 * Validates one envelope. Returns the typed event, or null for anything that is not exactly an
 * `agent.run-step.v1` event (unknown type, wrong field type, `eventId` not `<runId>:<seq>`).
 * Extra fields are tolerated (forward compatible) and stay on the returned object; the app only reads the
 * validated, typed fields. Callers that follow one run must also compare `runId` (see `subscribeRunEvents`).
 */
export function parseRunEvent(json: unknown): RunEvent | null {
  if (!isObject(json)) {
    return null;
  }
  const { eventId, runId, seq, type, occurredAt, data } = json;
  if (
    typeof eventId !== "string" ||
    typeof runId !== "string" ||
    typeof seq !== "number" ||
    !Number.isInteger(seq) ||
    seq < 1 ||
    typeof type !== "string" ||
    typeof occurredAt !== "string" ||
    Number.isNaN(Date.parse(occurredAt)) ||
    !isObject(data)
  ) {
    return null;
  }
  if (eventId !== `${runId}:${String(seq)}`) {
    return null;
  }
  if (!RUN_EVENT_TYPES.includes(type as RunEventType)) {
    return null;
  }
  const shape = shapes[type as RunEventType];
  for (const [field, check] of Object.entries(shape)) {
    if (!check(data[field])) {
      return null;
    }
  }
  return json as unknown as RunEvent;
}

/** Parses an array (the JSON export); invalid entries are dropped. */
export function parseRunEvents(json: unknown): RunEvent[] {
  if (!Array.isArray(json)) {
    return [];
  }
  return mergeEvents(
    [],
    json.map(parseRunEvent).filter((e): e is RunEvent => e !== null),
  );
}

/** Merges by `seq`: dedupes (a resumed stream may repeat) and keeps ascending order. */
export function mergeEvents(
  existing: readonly RunEvent[],
  incoming: readonly RunEvent[],
): RunEvent[] {
  const bySeq = new Map<number, RunEvent>();
  for (const event of existing) {
    bySeq.set(event.seq, event);
  }
  for (const event of incoming) {
    if (!bySeq.has(event.seq)) {
      bySeq.set(event.seq, event);
    }
  }
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
}
