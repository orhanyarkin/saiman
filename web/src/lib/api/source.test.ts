import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { apiGet, apiPost, ApiError, subscribeRunEvents } from "@/lib/api/source";
import { connect, disconnect, initTokenStore, mustAuthenticate } from "@/lib/auth/token-store";
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
    sessionStorage.clear();
    initTokenStore();
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

  it("sends Authorization: Bearer on GET and POST once connected, never in the URL", async () => {
    connect("reader_token_0123456789abcdef0123456789", false);
    vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response("{}", { status: 200 })));
    await apiGet("/api/v1/runs?limit=5");
    await apiPost("/api/v1/runs", { question: "q" });
    for (const [url, init] of vi.mocked(fetch).mock.calls) {
      expect(JSON.stringify(url)).not.toContain("reader_token");
      expect((init?.headers as Record<string, string>).Authorization).toBe(
        "Bearer reader_token_0123456789abcdef0123456789",
      );
    }
    disconnect();
    await apiGet("/api/v1/runs");
    expect(
      (vi.mocked(fetch).mock.calls.at(-1)?.[1]?.headers as Record<string, string>).Authorization,
    ).toBeUndefined();
  });

  it("401 clears the token, opens the dialog state and stops further calls until connected", async () => {
    connect("old_token_0123456789abcdef0123456789ab", true);
    vi.mocked(fetch).mockResolvedValue(problem(401, "Authentication required"));
    await expect(apiGet("/api/v1/me")).rejects.toMatchObject({ kind: "unauthorized" });
    expect(sessionStorage.getItem("saiman.apiToken")).toBeNull();
    expect(mustAuthenticate()).toBe(true);
    vi.mocked(fetch).mockClear();
    await expect(apiGet("/api/v1/runs")).rejects.toMatchObject({ kind: "unauthorized" });
    expect(fetch).not.toHaveBeenCalled();
    connect("new_token_0123456789abcdef0123456789ab", false);
    expect(mustAuthenticate()).toBe(false);
  });

  it("recognises the daily-cap problem and keeps its code and replay hint", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(
        JSON.stringify({
          type: "urn:saiman:problem:llm-daily-cap",
          code: "LLM_DAILY_CAP_REACHED",
          replayAvailable: true,
          detail: "Daily budget used.",
        }),
        {
          status: 503,
          headers: { "content-type": "application/problem+json", "Retry-After": "60" },
        },
      ),
    );
    const error = await apiPost("/api/v1/runs", {}).catch((e: unknown) => e);
    expect(error).toMatchObject({
      kind: "daily-cap",
      code: "LLM_DAILY_CAP_REACHED",
      replayAvailable: true,
      retryAfterSeconds: 60,
    });
  });

  it("keeps a plain 503 as not-ready", async () => {
    vi.mocked(fetch).mockResolvedValue(problem(503, "starting"));
    await expect(apiGet("/api/v1/x")).rejects.toMatchObject({ kind: "not-ready", code: null });
  });

  it.each([
    [409, "conflict"],
    [401, "unauthorized"],
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

const RUN = "6ad4354c-8e79-4b49-b5d9-d45eb9689b41";
const envelope = (seq: number, type: string, data: unknown) => ({
  eventId: `${RUN}:${String(seq)}`,
  runId: RUN,
  seq,
  type,
  occurredAt: "2026-10-01T10:00:00Z",
  data,
});

const frame = (seq: number, type: string, data: unknown, runId = RUN) =>
  `id: ${String(seq)}\nevent: ${type}\ndata: ${JSON.stringify({ ...envelope(seq, type, data), runId, eventId: `${runId}:${String(seq)}` })}\n\n`;

function sse(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk));
        }
        controller.close();
      },
    }),
    { status, headers: { "content-type": "text/event-stream" } },
  );
}

const cost = { atomicUnits: 0, asset: "USD", decimals: 6 };
const failed = (seq: number) =>
  frame(seq, "RUN_FAILED", {
    failureCode: "RUN_DEADLINE",
    costSoFar: { paymentsUsdc: cost, llmUsd: cost, totalUsd: cost },
  });

describe("subscribeRunEvents", () => {
  beforeEach(() => {
    sessionStorage.clear();
    initTokenStore();
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("delivers validated events, drops invalid ones and closes on the terminal event", async () => {
    connect("reader_token_0123456789abcdef0123456789", false);
    vi.mocked(fetch).mockResolvedValue(
      sse([
        frame(1, "STEP_STARTED", { step: "PLANNER" }),
        frame(2, "STEP_COMPLETED", { step: "NOPE" }),
        failed(3),
      ]),
    );
    const seen: RunEvent[] = [];
    const onClose = vi.fn();
    subscribeRunEvents(RUN, (e) => seen.push(e), { onClose });
    await vi.waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1);
    });
    expect(seen.map((e) => e.seq)).toEqual([1, 3]);

    const [url, init] = vi.mocked(fetch).mock.calls[0] ?? [];
    expect(url).toBe(`/api/v1/runs/${RUN}/events`);
    expect(init?.headers).toMatchObject({
      Authorization: "Bearer reader_token_0123456789abcdef0123456789",
      Accept: "text/event-stream",
    });
    expect(fetch).toHaveBeenCalledTimes(1); // the terminal event stops the loop: no reconnect
  });

  it("drops an event that belongs to another run", async () => {
    const other = "00000000-0000-4000-8000-000000000000";
    vi.mocked(fetch).mockResolvedValue(
      sse([frame(1, "STEP_STARTED", { step: "PLANNER" }, other), failed(2)]),
    );
    const seen: RunEvent[] = [];
    const onClose = vi.fn();
    subscribeRunEvents(RUN, (e) => seen.push(e), { onClose });
    await vi.waitFor(() => {
      expect(onClose).toHaveBeenCalled();
    });
    expect(seen.map((e) => e.runId)).toEqual([RUN]);
  });

  it("stops on HTTP 204 (nothing left)", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));
    const onClose = vi.fn();
    subscribeRunEvents(RUN, () => undefined, { onClose });
    await vi.waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("stops on 401, drops the token and asks for a new one", async () => {
    connect("old_token_0123456789abcdef0123456789ab", true);
    vi.mocked(fetch).mockResolvedValue(problem(401, "Authentication required"));
    const onClose = vi.fn();
    subscribeRunEvents(RUN, () => undefined, { onClose });
    await vi.waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1);
    });
    expect(mustAuthenticate()).toBe(true);
    expect(sessionStorage.getItem("saiman.apiToken")).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("reconnects with Last-Event-ID after the stream ends without a terminal event", async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(sse(["retry: 10\n\n", frame(1, "STEP_STARTED", { step: "PLANNER" })]))
      .mockResolvedValueOnce(sse([failed(2)]));
    const seen: RunEvent[] = [];
    const onReconnecting = vi.fn();
    const onClose = vi.fn();
    subscribeRunEvents(RUN, (e) => seen.push(e), { onClose, onReconnecting });
    await vi.waitFor(() => {
      expect(onClose).toHaveBeenCalled();
    });
    expect(onReconnecting).toHaveBeenCalledTimes(1);
    expect(seen.map((e) => e.seq)).toEqual([1, 2]);
    const second = vi.mocked(fetch).mock.calls[1]?.[1]?.headers as Record<string, string>;
    expect(second["Last-Event-ID"]).toBe("1");
  });
});
