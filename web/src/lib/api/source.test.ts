import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { apiGet, apiPost, ApiError, subscribeRunEvents } from "@/lib/api/source";
import type { RunEvent } from "@/lib/api/run-events";

function problem(status: number, detail: string, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify({ status, detail }), {
    status,
    statusText: "x",
    headers: { "content-type": "application/problem+json", ...headers },
  });
}

describe("api requests", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("apiPost always sends JSON content type and the CSRF header", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response("{}", { status: 202 }));
    await apiPost("/api/v1/runs", { question: "q" });
    const init = vi.mocked(fetch).mock.calls[0]?.[1];
    expect(init?.method).toBe("POST");
    expect(init?.headers).toMatchObject({
      "Content-Type": "application/json",
      "X-Saiman-Csrf": "1",
    });
  });

  it.each([
    [409, "conflict"],
    [503, "not-ready"],
    [404, "not-found"],
    [400, "invalid"],
    [403, "forbidden"],
    [500, "http"],
  ])("maps %d to kind %s and keeps the Problem Details detail", async (status, kind) => {
    vi.mocked(fetch).mockResolvedValue(problem(status, "fixed text"));
    const error = await apiGet("/api/v1/x").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind, status, detail: "fixed text" });
  });

  it("reads Retry-After on 429", async () => {
    vi.mocked(fetch).mockResolvedValue(problem(429, "too many", { "Retry-After": "5" }));
    const error = await apiGet("/api/v1/x").catch((e: unknown) => e);
    expect(error).toMatchObject({ kind: "rate-limited", retryAfterSeconds: 5 });
  });

  it("ignores a hostile Retry-After and an oversized detail", async () => {
    vi.mocked(fetch).mockResolvedValue(
      problem(429, "x".repeat(400), { "Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT" }),
    );
    const error = await apiGet("/api/v1/x").catch((e: unknown) => e);
    expect(error).toMatchObject({ retryAfterSeconds: null, detail: null });
  });

  it("reports a network failure as a typed error", async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(apiGet("/api/v1/x")).rejects.toMatchObject({ kind: "network" });
  });
});

class FakeEventSource {
  static CLOSED = 2;
  static instances: FakeEventSource[] = [];
  readyState = 1;
  onerror: (() => void) | null = null;
  closed = false;
  listeners = new Map<string, (m: MessageEvent) => void>();
  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }
  addEventListener(type: string, listener: (m: MessageEvent) => void) {
    this.listeners.set(type, listener);
  }
  close() {
    this.closed = true;
    this.readyState = 2;
  }
  emit(type: string, payload: unknown) {
    this.listeners.get(type)?.({ data: JSON.stringify(payload) } as MessageEvent);
  }
}

const RUN = "6ad4354c-8e79-4b49-b5d9-d45eb9689b41";
const envelope = (seq: number, type: string, data: unknown) => ({
  eventId: `${RUN}:${String(seq)}`,
  runId: RUN,
  seq,
  type,
  occurredAt: "2026-10-01T10:00:00Z",
  data,
});

describe("subscribeRunEvents", () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("delivers validated events, drops invalid ones and closes on the terminal event", () => {
    const seen: RunEvent[] = [];
    const onClose = vi.fn();
    subscribeRunEvents(RUN, (e) => seen.push(e), { onClose });
    const source = FakeEventSource.instances[0];
    expect(source?.url).toBe(`/api/v1/runs/${RUN}/events`);

    source?.emit("STEP_STARTED", envelope(1, "STEP_STARTED", { step: "PLANNER" }));
    source?.emit("STEP_COMPLETED", envelope(2, "STEP_COMPLETED", { step: "NOPE" }));
    expect(seen.map((e) => e.seq)).toEqual([1]);
    expect(source?.closed).toBe(false);

    const cost = { atomicUnits: 0, asset: "USD", decimals: 6 };
    source?.emit(
      "RUN_FAILED",
      envelope(3, "RUN_FAILED", {
        failureCode: "RUN_DEADLINE",
        costSoFar: { paymentsUsdc: cost, llmUsd: cost, totalUsd: cost },
      }),
    );
    expect(seen.map((e) => e.seq)).toEqual([1, 3]);
    expect(source?.closed).toBe(true);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("drops an event that belongs to another run", () => {
    const seen: RunEvent[] = [];
    subscribeRunEvents(RUN, (e) => seen.push(e));
    const source = FakeEventSource.instances[0];
    const other = "00000000-0000-4000-8000-000000000000";
    const foreign = {
      ...envelope(1, "STEP_STARTED", { step: "PLANNER" }),
      runId: other,
      eventId: `${other}:1`,
    };
    source?.emit("STEP_STARTED", foreign);
    source?.emit("STEP_STARTED", envelope(1, "STEP_STARTED", { step: "PLANNER" }));
    expect(seen.map((e) => e.runId)).toEqual([RUN]);
  });

  it("stops when the browser gives up (CLOSED, e.g. HTTP 204) and keeps waiting while it reconnects", () => {
    const onClose = vi.fn();
    const onReconnecting = vi.fn();
    subscribeRunEvents(RUN, () => undefined, { onClose, onReconnecting });
    const source = FakeEventSource.instances[0];

    source?.onerror?.();
    expect(onReconnecting).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();

    if (source) {
      source.readyState = 2;
    }
    source?.onerror?.();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(source?.closed).toBe(true);
  });
});
