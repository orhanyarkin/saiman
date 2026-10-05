/**
 * Fixture server for e2e and Lighthouse: a tiny stand-in for the orchestrator (and, in T3c, the
 * ledger) so the web app can be exercised without the real stack. Plain Node `http`, no
 * dependencies; Node >= 22.19 runs this file directly (`pnpm e2e:fixture`).
 *
 * It implements the capture format that `make capture-demo` will produce (ADR-0004):
 *
 *   { capturedAt, environment, responses: { "<GET path>": body }, runEvents: { runId: Envelope[] } }
 *
 * - `responses`: served verbatim for any matching GET (path including query string).
 * - `runEvents`: captured runs; `GET /api/v1/runs/{id}` is derived from the events and the events
 *   endpoint serves them as SSE or as the JSON export.
 * - `GET /api/v1/runs/{id}/payments` and `GET /api/v1/approvals` are derived from the stateful runs
 *   (a captured response for the same path wins; captured approvals are listed before live ones).
 * - Stateful on top: `POST /api/v1/runs` starts a scripted run built from the shared golden
 *   fixtures; the script pauses at PAYMENT_APPROVAL_REQUIRED until the approval POST arrives.
 *
 * It mimics the real request guard (JSON content type + `X-Saiman-Csrf: 1` on every POST), so a
 * client that forgets them fails here too.
 *
 * Authentication (ADR-0023), on by default:
 * - `Authorization: Bearer fixture-reader-<anything>` is a READER, `fixture-operator-<anything>` an
 *   OPERATOR. No/unknown token on any `/api` call: 401 + `WWW-Authenticate: Bearer`. A READER's
 *   POST: 403 (this token is read-only). A token in the query string: 400 (it must never be there).
 * - `GET /api/v1/me` answers `{name, roles}`.
 * - Auth off (`auth: false` / `SAIMAN_FIXTURE_AUTH=off`, used by the Lighthouse run): everything is
 *   open and `/me` says OPERATOR.
 * - LLM daily cap (ADR-0026): `llmCap: true` makes every `POST /api/v1/runs` answer 503
 *   `LLM_DAILY_CAP_REACHED`; a question containing `__LLM_CAP__` does so for that request only
 *   (specs run in parallel against one server), and `__LLM_CAP_MID__` starts a run that fails
 *   with that code.
 */
import { randomUUID } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

type Json = Record<string, unknown>;
interface Envelope {
  eventId: string;
  runId: string;
  seq: number;
  type: string;
  occurredAt: string;
  data: Json;
}
export interface Capture {
  /** Capture format v1 (ADR-0026) carries these; the M5 example shape has only the last four. */
  schemaVersion?: number;
  network?: string;
  sourceCommit?: string;
  capturedAt?: string;
  environment?: string;
  responses?: Record<string, unknown>;
  runEvents?: Record<string, Envelope[]>;
}
export interface FixtureOptions {
  port?: number;
  capture?: Capture;
  /** Delay between scripted events. */
  stepMs?: number;
  /** SSE `retry:` value and heartbeat comment interval. */
  retryMs?: number;
  heartbeatMs?: number;
  /** How long after PAYMENT_SETTLED the ledger "consumes Kafka" and shows the payment. */
  ledgerLagMs?: number;
  /** A scripted reconciliation run stays RUNNING this long. */
  reconRunMs?: number;
  /** POST /reconciliation/runs answers 429 this long after the previous start. */
  reconCooldownMs?: number;
  /** Require a bearer token on every /api call (default true). */
  auth?: boolean;
  /** Every POST /api/v1/runs answers 503 LLM_DAILY_CAP_REACHED. */
  llmCap?: boolean;
}

export const CAP_MARKER = "__LLM_CAP__";
export const CAP_MID_MARKER = "__LLM_CAP_MID__";
const FIXTURE_TOKEN = /^fixture-(reader|operator)-[A-Za-z0-9_-]+$/;

type FixtureRole = "READER" | "OPERATOR";

function roleOf(req: IncomingMessage): FixtureRole | null {
  const header = req.headers.authorization;
  const match = typeof header === "string" ? /^Bearer (\S+)$/.exec(header) : null;
  const token = FIXTURE_TOKEN.exec(match?.[1] ?? "");
  return token ? (token[1] === "operator" ? "OPERATOR" : "READER") : null;
}

interface ReconRun {
  id: string;
  startedAt: number;
}

const HERE = dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR = resolve(
  HERE,
  "../../libs/shared/src/test/resources/fixtures/events/agent.run-step.v1",
);

const TERMINAL = new Set(["RUN_COMPLETED", "RUN_FAILED"]);

function loadFixtures(): Record<string, Envelope> {
  const byType: Record<string, Envelope> = {};
  for (const name of readdirSync(FIXTURE_DIR)) {
    if (name.endsWith(".json")) {
      byType[name.replace(".json", "")] = JSON.parse(
        readFileSync(resolve(FIXTURE_DIR, name), "utf8"),
      ) as Envelope;
    }
  }
  return byType;
}

interface Step {
  type: string;
  data: Json;
  waitForApproval?: true;
}

/** Scripted run built from the shared fixture envelopes (data only; ids and seq are renumbered). */
function buildScript(fx: Record<string, Envelope>, question: string, budget: number): Step[] {
  const data = (type: string): Json => structuredClone(fx[type]?.data ?? {});
  const step = (type: string, name: string): Step => ({
    type,
    data: { ...data(type), step: name },
  });
  return [
    {
      type: "RUN_STARTED",
      data: { question, budget: { atomicUnits: budget, asset: "USDC", decimals: 6 } },
    },
    step("STEP_STARTED", "PLANNER"),
    { type: "PLAN_CREATED", data: data("PLAN_CREATED") },
    step("STEP_COMPLETED", "PLANNER"),
    step("STEP_STARTED", "RESEARCHER"),
    { type: "TOOL_CALL_REQUESTED", data: data("TOOL_CALL_REQUESTED") },
    {
      type: "PAYMENT_APPROVAL_REQUIRED",
      data: data("PAYMENT_APPROVAL_REQUIRED"),
      waitForApproval: true,
    },
  ];
}

function tail(fx: Record<string, Envelope>, approved: boolean): Step[] {
  const data = (type: string): Json => structuredClone(fx[type]?.data ?? {});
  const step = (type: string, name: string): Step => ({
    type,
    data: { ...data(type), step: name },
  });
  const out: Step[] = [];
  if (approved) {
    out.push({ type: "PAYMENT_SETTLED", data: data("PAYMENT_SETTLED") });
    out.push({ type: "TOOL_CALL_COMPLETED", data: data("TOOL_CALL_COMPLETED") });
    out.push(step("STEP_COMPLETED", "RESEARCHER"));
    out.push(step("STEP_STARTED", "RISK"), step("STEP_COMPLETED", "RISK"));
    out.push(step("STEP_STARTED", "SYNTHESIS"));
    out.push({ type: "MODEL_CALL_COMPLETED", data: data("MODEL_CALL_COMPLETED") });
    out.push(step("STEP_COMPLETED", "SYNTHESIS"));
    out.push({ type: "RUN_COMPLETED", data: data("RUN_COMPLETED") });
  } else {
    out.push({
      type: "PAYMENT_DENIED",
      data: { ...data("PAYMENT_DENIED"), reason: "APPROVAL_REJECTED" },
    });
    out.push({ type: "RUN_FAILED", data: { ...data("RUN_FAILED"), failureCode: "NO_EVIDENCE" } });
  }
  return out;
}

interface Run {
  id: string;
  events: Envelope[];
  listeners: Set<(event: Envelope) => void>;
  queue: Step[];
  approval: { approvalId: string; status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" } | null;
  waiting: boolean;
  timer: NodeJS.Timeout | null;
}

function problem(res: ServerResponse, status: number, detail: string, headers: Json = {}) {
  res.writeHead(status, { "Content-Type": "application/problem+json", ...headers });
  res.end(JSON.stringify({ type: "about:blank", title: detail, status, detail }));
}

function json(res: ServerResponse, status: number, body: unknown) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

async function readBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks).toString("utf8");
}

const money = (atomicUnits: number, asset = "USDC") => ({ atomicUnits, asset, decimals: 6 });

/** The run summary the orchestrator would return for these events (what the examples capture). */
export function summarizeRun(runId: string, events: readonly Envelope[], pending = false) {
  const started = events.find((e) => e.type === "RUN_STARTED");
  const done = events.find((e) => e.type === "RUN_COMPLETED");
  const failed = events.find((e) => e.type === "RUN_FAILED");
  const settled = events
    .filter((e) => e.type === "PAYMENT_SETTLED")
    .reduce((sum, e) => sum + (e.data.amount as { atomicUnits: number }).atomicUnits, 0);
  const pendingAmount =
    (
      events.findLast((e) => e.type === "PAYMENT_APPROVAL_REQUIRED")?.data.amount as
        { atomicUnits: number } | undefined
    )?.atomicUnits ?? 0;
  const zero = { paymentsUsdc: money(0), llmUsd: money(0, "USD"), totalUsd: money(0, "USD") };
  const finalCost = (done?.data.cost ?? failed?.data.costSoFar ?? null) as typeof zero | null;
  return {
    runId,
    status: done ? "SUCCEEDED" : failed ? "FAILED" : pending ? "AWAITING_APPROVAL" : "RUNNING",
    question: (started?.data.question as string | undefined) ?? "",
    budget: started?.data.budget ?? money(0),
    reserved: money(pending ? pendingAmount : 0),
    committed: money(settled),
    cost: finalCost ?? zero,
    failureCode: (failed?.data.failureCode as string | undefined) ?? null,
    traceId: null,
    createdAt: events[0]?.occurredAt ?? new Date().toISOString(),
    startedAt: events[0]?.occurredAt ?? null,
    finishedAt: done?.occurredAt ?? failed?.occurredAt ?? null,
    report: done?.data.report ?? null,
  };
}

/** Payment intents derived from a run's events (what `GET /runs/{id}/payments` returns). */
export function paymentItemsOf(events: readonly Envelope[]) {
  const items = new Map<string, Json>();
  const byApproval = new Map<string, string>();
  let tool = "unknown";
  for (const event of events) {
    const d = event.data;
    if (event.type === "TOOL_CALL_REQUESTED") {
      tool = String(d.tool);
    } else if (event.type === "PAYMENT_APPROVAL_REQUIRED") {
      const intentId = String(d.paymentIntentId);
      byApproval.set(String(d.approvalId), intentId);
      items.set(intentId, {
        paymentIntentId: intentId,
        tool,
        resource: d.resource,
        payTo: d.payTo,
        amount: d.amount,
        status: "AWAITING_APPROVAL",
        txHash: null,
        createdAt: event.occurredAt,
        updatedAt: event.occurredAt,
      });
    } else if (event.type === "PAYMENT_APPROVAL_DECIDED") {
      const item = items.get(byApproval.get(String(d.approvalId)) ?? "");
      if (item) {
        item.status = d.decision;
        item.updatedAt = event.occurredAt;
      }
    } else if (event.type === "PAYMENT_SETTLED") {
      const item = items.get(String(d.paymentIntentId));
      if (item) {
        item.status = "SETTLED";
        item.txHash = d.txHash;
        item.updatedAt = event.occurredAt;
      }
    }
  }
  return [...items.values()];
}

export function createFixtureServer(options: FixtureOptions = {}): Server {
  const stepMs = options.stepMs ?? 150;
  const retryMs = options.retryMs ?? 1000;
  const heartbeatMs = options.heartbeatMs ?? 15_000;
  const ledgerLagMs = options.ledgerLagMs ?? 800;
  const reconRunMs = options.reconRunMs ?? 1500;
  const reconCooldownMs = options.reconCooldownMs ?? 4000;
  const capture = options.capture ?? {};
  const authOn = options.auth ?? true;
  const fixtures = loadFixtures();
  const runs = new Map<string, Run>();
  const reconRuns: ReconRun[] = [];

  for (const [runId, events] of Object.entries(capture.runEvents ?? {})) {
    runs.set(runId, {
      id: runId,
      events,
      listeners: new Set(),
      queue: [],
      approval: null,
      waiting: false,
      timer: null,
    });
  }

  function emit(run: Run, type: string, data: Json) {
    const seq = run.events.length + 1;
    const event: Envelope = {
      eventId: `${run.id}:${String(seq)}`,
      runId: run.id,
      seq,
      type,
      occurredAt: new Date().toISOString(),
      data,
    };
    run.events.push(event);
    for (const listener of run.listeners) {
      listener(event);
    }
  }

  function isTerminal(run: Run): boolean {
    return run.events.some((e) => TERMINAL.has(e.type));
  }

  function advance(run: Run) {
    if (run.waiting || isTerminal(run)) {
      return;
    }
    const next = run.queue.shift();
    if (next === undefined) {
      stop(run);
      return;
    }
    if (next.waitForApproval) {
      // The golden fixture's expiry is in the past; a live approval always gets five minutes.
      next.data.expiresAt = new Date(Date.now() + 5 * 60_000).toISOString();
      // Unique ids per run: the golden fixture's would collide across runs in the approvals list.
      next.data.approvalId = randomUUID();
      next.data.paymentIntentId = randomUUID();
    }
    emit(run, next.type, next.data);
    if (next.waitForApproval) {
      const approvalId = String(next.data.approvalId);
      run.approval = { approvalId, status: "PENDING" };
      run.waiting = true;
    }
  }

  function stop(run: Run) {
    if (run.timer) {
      clearInterval(run.timer);
      run.timer = null;
    }
  }

  function startRun(question: string, budget: number): Run {
    const id = randomUUID();
    const run: Run = {
      id,
      events: [],
      listeners: new Set(),
      queue: buildScript(fixtures, question, budget),
      approval: null,
      waiting: false,
      timer: null,
    };
    runs.set(id, run);
    run.timer = setInterval(() => {
      advance(run);
    }, stepMs);
    run.timer.unref();
    return run;
  }

  function decide(run: Run, decision: "APPROVE" | "REJECT") {
    const approval = run.approval;
    if (!approval) {
      return;
    }
    approval.status = decision === "APPROVE" ? "APPROVED" : "REJECTED";
    emit(run, "PAYMENT_APPROVAL_DECIDED", {
      approvalId: approval.approvalId,
      decision: approval.status,
    });
    // Continue with the script that matches the decision.
    run.queue = tail(fixtures, decision === "APPROVE");
    const required = run.events.findLast((e) => e.type === "PAYMENT_APPROVAL_REQUIRED");
    for (const step of run.queue) {
      if (step.type === "PAYMENT_SETTLED" && required) {
        step.data.paymentIntentId = required.data.paymentIntentId;
      }
    }
    run.waiting = false;
  }

  const summary = (run: Run) =>
    summarizeRun(run.id, run.events, run.approval?.status === "PENDING");
  const paymentItems = (run: Run) => paymentItemsOf(run.events);

  /** Pending approvals of the stateful runs (`GET /approvals?status=PENDING`). */
  function pendingApprovals() {
    const out: Json[] = [];
    for (const run of runs.values()) {
      if (run.approval?.status !== "PENDING") {
        continue;
      }
      const event = run.events.findLast((e) => e.type === "PAYMENT_APPROVAL_REQUIRED");
      if (!event) {
        continue;
      }
      out.push({
        id: run.approval.approvalId,
        runId: run.id,
        paymentIntentId: event.data.paymentIntentId,
        amountAtomic: (event.data.amount as { atomicUnits: number }).atomicUnits,
        payTo: event.data.payTo,
        resource: event.data.resource,
        status: "PENDING",
        requestedAt: event.occurredAt,
        expiresAt: event.data.expiresAt,
        decidedAt: null,
      });
    }
    return out;
  }

  // ---- ledger stand-in: payments of the stateful runs, and a scripted reconciliation ----------

  const PAYEE = "0x1111111111111111111111111111111111111111";
  const PAYER = "0x2222222222222222222222222222222222222222";

  /** A run's settled payments as the ledger shows them once it has consumed the events. */
  function ledgerPayments(run: Run): Json[] {
    const out: Json[] = [];
    for (const settled of run.events.filter((e) => e.type === "PAYMENT_SETTLED")) {
      if (Date.now() - Date.parse(settled.occurredAt) < ledgerLagMs) {
        continue; // Kafka is asynchronous: the ledger is a little behind the orchestrator.
      }
      const intentId = String(settled.data.paymentIntentId);
      const required = run.events.find(
        (e) => e.type === "PAYMENT_APPROVAL_REQUIRED" && e.data.paymentIntentId === intentId,
      );
      out.push({
        paymentId: intentId,
        runId: run.id,
        payTo: required?.data.payTo ?? PAYEE,
        amount: settled.data.amount,
        buyerState: "SETTLED",
        sellerState: "SETTLED",
        chainState: "UNKNOWN",
        buyerTxHash: settled.data.txHash ?? null,
        sellerTxHash: settled.data.txHash ?? null,
        chainTxHash: null,
        createdAt: required?.occurredAt ?? settled.occurredAt,
        updatedAt: settled.occurredAt,
      });
    }
    return out;
  }

  function ledgerPaymentDetail(paymentId: string): Json | null {
    for (const run of runs.values()) {
      const payment = ledgerPayments(run).find((p) => p.paymentId === paymentId);
      if (!payment) {
        continue;
      }
      const amount = payment.amount;
      const entry = (n: number, book: string, kind: string, description: string, p: Json[]) => ({
        entryId: `e0000000-0000-4000-8000-0000000000${String(n).padStart(2, "0")}`,
        book,
        kind,
        description,
        effectiveAt: payment.updatedAt,
        reversesEntryId: null,
        postings: p,
      });
      const posting = (accountCode: string, side: string) => ({ accountCode, side, amount });
      const buyer = `buyer:${PAYER}`;
      const seller = `seller:${String(payment.payTo)}`;
      return {
        payment,
        entries: [
          entry(1, "BUYER", "ENCUMBER", "Authorization signed", [
            posting(`${buyer}:wallet:encumbered`, "DEBIT"),
            posting(`${buyer}:wallet:available`, "CREDIT"),
          ]),
          entry(2, "BUYER", "SETTLE", "Payment settled", [
            posting(`${buyer}:expense:data`, "DEBIT"),
            posting(`${buyer}:wallet:encumbered`, "CREDIT"),
          ]),
          entry(3, "SELLER", "SALE", "Payment received", [
            posting(`${seller}:wallet`, "DEBIT"),
            posting(`${seller}:revenue:data`, "CREDIT"),
          ]),
        ],
        mismatches: [],
      };
    }
    return null;
  }

  const reconDone = (r: ReconRun) => Date.now() - r.startedAt >= reconRunMs;
  const capturedLatest = (): Json =>
    (capture.responses?.["/api/v1/reconciliation/runs/latest"] as Json | undefined) ?? {};

  function reconReport(r: ReconRun): Json {
    const done = reconDone(r);
    const base = structuredClone(capturedLatest());
    return {
      ...base,
      runId: r.id,
      status: done ? "COMPLETED" : "RUNNING",
      startedAt: new Date(r.startedAt).toISOString(),
      finishedAt: done ? new Date(r.startedAt + reconRunMs).toISOString() : null,
      ...(done
        ? {}
        : {
            items: [],
            summary: {
              checked: 0,
              matched: 0,
              pending: 0,
              mismatches: 0,
              resolvedUsed: 0,
              resolvedUnused: 0,
            },
          }),
    };
  }

  function reconSummary(r: ReconRun): Json {
    const report = reconReport(r);
    return {
      runId: report.runId,
      status: report.status,
      startedAt: report.startedAt,
      finishedAt: report.finishedAt,
      safeBlock: report.safeBlock ?? null,
      summary: report.summary,
    };
  }

  function startReconciliation(res: ServerResponse) {
    const last = reconRuns.at(-1);
    if (last && !reconDone(last)) {
      problem(res, 409, "a reconciliation run is already in progress");
      return;
    }
    if (last && Date.now() - last.startedAt < reconCooldownMs) {
      const wait = Math.max(1, Math.ceil((last.startedAt + reconCooldownMs - Date.now()) / 1000));
      problem(res, 429, "reconciliation was started too recently", { "Retry-After": String(wait) });
      return;
    }
    const run = { id: randomUUID(), startedAt: Date.now() };
    reconRuns.push(run);
    json(res, 202, { runId: run.id });
  }

  function streamEvents(req: IncomingMessage, res: ServerResponse, run: Run) {
    const rawLast = req.headers["last-event-id"];
    const last = typeof rawLast === "string" && /^\d{1,9}$/.test(rawLast) ? Number(rawLast) : 0;
    const remaining = run.events.filter((e) => e.seq > last);
    if (isTerminal(run) && remaining.length === 0) {
      res.writeHead(204);
      res.end();
      return;
    }
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    res.write(`retry: ${String(retryMs)}\n\n`);
    const state = { ended: false };
    const write = (event: Envelope) => {
      res.write(
        `id: ${String(event.seq)}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`,
      );
      if (TERMINAL.has(event.type)) {
        finish();
      }
    };
    const finish = () => {
      if (state.ended) {
        return;
      }
      state.ended = true;
      clearInterval(heartbeat);
      run.listeners.delete(write);
      res.end();
    };
    const heartbeat = setInterval(() => {
      res.write(":heartbeat\n\n");
    }, heartbeatMs);
    heartbeat.unref();
    for (const event of remaining) {
      write(event);
    }
    if (!state.ended) {
      run.listeners.add(write);
      req.on("close", finish);
    }
  }

  return createServer((req, res) => {
    void handle(req, res);
  });

  async function handle(req: IncomingMessage, res: ServerResponse) {
    const url = new URL(req.url ?? "/", "http://fixture.local");
    const path = url.pathname;
    const method = req.method ?? "GET";

    // A credential in the URL would end up in access logs: refuse it outright (ADR-0023).
    for (const name of ["token", "access_token", "authorization", "api_token"]) {
      if (url.searchParams.has(name)) {
        problem(res, 400, "credentials belong in the Authorization header");
        return;
      }
    }

    if (method === "POST") {
      const contentType = req.headers["content-type"] ?? "";
      if (!contentType.startsWith("application/json") || req.headers["x-saiman-csrf"] !== "1") {
        problem(res, 403, "request rejected");
        return;
      }
    }

    // The real services run their request guard first, then authentication (ADR-0023).
    const role: FixtureRole | null = authOn ? roleOf(req) : "OPERATOR";
    if (path.startsWith("/api/") && role === null) {
      problem(res, 401, "Authentication required", { "WWW-Authenticate": "Bearer" });
      return;
    }
    if (method === "POST" && role === "READER") {
      problem(res, 403, "Access denied: this token is read-only");
      return;
    }

    if (method === "GET" && path === "/api/v1/me") {
      json(
        res,
        200,
        !authOn
          ? { name: "anonymous", roles: ["OPERATOR"] }
          : role === "OPERATOR"
            ? { name: "operator:fixture1", roles: ["OPERATOR"] }
            : { name: "reader:fixture2", roles: ["READER"] },
      );
      return;
    }

    if (method === "GET" && path === "/api/v1/ping") {
      json(res, 200, {
        service: "orchestrator (fixture)",
        dbTime: new Date().toISOString(),
        traceId: null,
      });
      return;
    }

    if (method === "POST" && path === "/api/v1/runs") {
      let body: Json;
      try {
        body = JSON.parse(await readBody(req)) as Json;
      } catch {
        problem(res, 400, "invalid request body");
        return;
      }
      const question = typeof body.question === "string" ? body.question.trim() : "";
      if (question.length < 3 || question.length > 500) {
        problem(res, 400, "question must be 3 to 500 characters");
        return;
      }
      if (options.llmCap === true || question.includes(CAP_MARKER)) {
        res.writeHead(503, {
          "Content-Type": "application/problem+json",
          "Retry-After": "3600",
        });
        res.end(
          JSON.stringify({
            type: "urn:saiman:problem:llm-daily-cap",
            title: "Daily model budget reached",
            status: 503,
            detail: "The daily model budget is used up. New runs resume at 00:00 UTC.",
            code: "LLM_DAILY_CAP_REACHED",
            replayAvailable: true,
          }),
        );
        return;
      }
      const budget = typeof body.budgetAtomic === "number" ? body.budgetAtomic : 50_000;
      const run = startRun(question, budget);
      if (question.includes(CAP_MID_MARKER)) {
        // The cap is reached mid-run: the run fails with the dedicated code (ADR-0026).
        run.queue = [
          ...run.queue.slice(0, 2),
          {
            type: "RUN_FAILED",
            data: {
              ...structuredClone(fixtures.RUN_FAILED?.data ?? {}),
              failureCode: "LLM_DAILY_CAP_REACHED",
            },
          },
        ];
      }
      res.setHeader("Location", `/api/v1/runs/${run.id}`);
      json(res, 202, { runId: run.id, eventsUrl: `/api/v1/runs/${run.id}/events`, traceId: null });
      return;
    }

    if (method === "POST" && path === "/api/v1/reconciliation/runs") {
      startReconciliation(res);
      return;
    }

    if (method === "GET" && path.startsWith("/api/v1/reconciliation/runs")) {
      const reconMatch = /^\/api\/v1\/reconciliation\/runs\/([^/]+)$/.exec(path);
      const latest = reconRuns.at(-1);
      if (reconMatch?.[1] === "latest" && latest) {
        json(res, 200, reconReport(latest));
        return;
      }
      const own = reconRuns.find((r) => r.id === reconMatch?.[1]);
      if (own) {
        json(res, 200, reconReport(own));
        return;
      }
      if (path === "/api/v1/reconciliation/runs" && reconRuns.length > 0) {
        const captured = capture.responses?.[path + url.search] ?? capture.responses?.[path];
        const older = (captured as { items?: Json[] } | undefined)?.items ?? [];
        json(res, 200, { items: [...reconRuns.toReversed().map(reconSummary), ...older] });
        return;
      }
    }

    const ledgerRun = url.searchParams.get("runId");
    if (method === "GET" && path === "/api/v1/ledger/payments" && ledgerRun) {
      const run = runs.get(ledgerRun);
      if (run && capture.responses?.[path + url.search] === undefined) {
        json(res, 200, { items: ledgerPayments(run), nextCursor: null });
        return;
      }
    }
    const ledgerPaymentMatch = /^\/api\/v1\/ledger\/payments\/([^/]+)$/.exec(path);
    if (method === "GET" && ledgerPaymentMatch) {
      const detail = ledgerPaymentDetail(ledgerPaymentMatch[1] ?? "");
      if (detail) {
        json(res, 200, detail);
        return;
      }
    }

    const approvalMatch = /^\/api\/v1\/runs\/([^/]+)\/approvals\/([^/]+)$/.exec(path);
    if (method === "POST" && approvalMatch) {
      const run = runs.get(approvalMatch[1] ?? "");
      if (!run?.approval || run.approval.approvalId !== approvalMatch[2]) {
        problem(res, 404, "approval not found");
        return;
      }
      let decision: unknown;
      try {
        decision = (JSON.parse(await readBody(req)) as Json).decision;
      } catch {
        decision = null;
      }
      if (decision !== "APPROVE" && decision !== "REJECT") {
        problem(res, 400, "decision must be APPROVE or REJECT");
        return;
      }
      if (run.approval.status !== "PENDING") {
        problem(
          res,
          409,
          run.approval.status === "EXPIRED"
            ? "approval has expired"
            : "approval was already decided",
        );
        return;
      }
      decide(run, decision);
      json(res, 200, { approvalId: run.approval.approvalId, status: run.approval.status });
      return;
    }

    const eventsMatch = /^\/api\/v1\/runs\/([^/]+)\/events$/.exec(path);
    if (method === "GET" && eventsMatch) {
      const run = runs.get(eventsMatch[1] ?? "");
      if (!run) {
        problem(res, 404, "run not found");
        return;
      }
      if ((req.headers.accept ?? "").includes("text/event-stream")) {
        streamEvents(req, res, run);
      } else {
        json(res, 200, run.events);
      }
      return;
    }

    const paymentsMatch = /^\/api\/v1\/runs\/([^/]+)\/payments$/.exec(path);
    if (method === "GET" && paymentsMatch) {
      const captured = capture.responses?.[path];
      const run = runs.get(paymentsMatch[1] ?? "");
      if (captured !== undefined) {
        json(res, 200, captured);
      } else if (run) {
        json(res, 200, { items: paymentItems(run) });
      } else {
        problem(res, 404, "run not found");
      }
      return;
    }

    if (method === "GET" && path === "/api/v1/approvals") {
      // Captured pending approvals first, then those of the runs started against this server.
      const captured = capture.responses?.[path + url.search] ?? capture.responses?.[path];
      const live = pendingApprovals();
      json(res, 200, Array.isArray(captured) ? [...(captured as Json[]), ...live] : live);
      return;
    }

    const runMatch = /^\/api\/v1\/runs\/([^/]+)$/.exec(path);
    if (method === "GET" && runMatch) {
      const run = runs.get(runMatch[1] ?? "");
      if (!run) {
        problem(res, 404, "run not found");
        return;
      }
      json(res, 200, summary(run));
      return;
    }

    if (method === "GET") {
      const captured = capture.responses?.[path + url.search] ?? capture.responses?.[path];
      if (captured !== undefined) {
        json(res, 200, captured);
        return;
      }
    }
    problem(res, 404, "not found");
  }
}

function main() {
  const port = Number(process.env.SAIMAN_FIXTURE_PORT ?? 4010);
  const capturePath = process.env.SAIMAN_CAPTURE;
  const read = (path: string) => JSON.parse(readFileSync(path, "utf8")) as Capture;
  // A real capture holds both services' responses; the examples are split per service.
  const capture = capturePath
    ? read(capturePath)
    : (() => {
        const orchestrator = read(resolve(HERE, "fixtures/capture.example.json"));
        const ledger = read(resolve(HERE, "fixtures/capture.ledger.example.json"));
        return { ...orchestrator, responses: { ...orchestrator.responses, ...ledger.responses } };
      })();
  const auth = process.env.SAIMAN_FIXTURE_AUTH !== "off";
  const stepMs = process.env.SAIMAN_STEP_MS ? Number(process.env.SAIMAN_STEP_MS) : undefined;
  const server = createFixtureServer({
    port,
    capture,
    auth,
    ...(stepMs === undefined ? {} : { stepMs }),
  });
  server.listen(port, "127.0.0.1", () => {
    console.log(`fixture server on http://127.0.0.1:${String(port)}`);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
