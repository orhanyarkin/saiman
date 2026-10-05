import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clampGap,
  MAX_GAP_MS,
  MIN_GAP_MS,
  normalizeCapture,
  replayDelays,
  resetCaptureForTests,
} from "@/lib/api/replay";
import type { RunEvent } from "@/lib/api/run-events";

const RUN = "6ad4354c-8e79-4b49-b5d9-d45eb9689b41";
const at = (offsetMs: number) =>
  new Date(Date.parse("2026-10-01T10:00:00Z") + offsetMs).toISOString();
const event = (seq: number, offsetMs: number, type = "STEP_STARTED"): unknown => ({
  eventId: `${RUN}:${String(seq)}`,
  runId: RUN,
  seq,
  type,
  occurredAt: at(offsetMs),
  data: { step: "PLANNER" },
});

const v1 = {
  schemaVersion: 1,
  capturedAt: "2026-10-01T10:00:00Z",
  environment: "AWS eu-central-1",
  network: "base-sepolia",
  sourceCommit: "abc1234",
  responses: {
    "/api/v1/ping": { service: "orchestrator" },
    "/api/v1/spend": { day: "2026-10-01" },
  },
  runEvents: { [RUN]: [event(1, 0), event(2, 10), event(3, 5000), { junk: true }] },
};

describe("normalizeCapture", () => {
  it("accepts capture format v1", () => {
    const capture = normalizeCapture(v1);
    expect(capture).toMatchObject({
      schemaVersion: 1,
      environment: "AWS eu-central-1",
      network: "base-sepolia",
      sourceCommit: "abc1234",
    });
    expect(capture.runEvents[RUN]).toHaveLength(3); // the invalid entry is dropped
  });

  it("accepts the M5 fixture shape (no schemaVersion, network or sourceCommit)", () => {
    const capture = normalizeCapture({
      capturedAt: "2026-10-01T10:00:00Z",
      environment: "local fixture",
      responses: {},
    });
    expect(capture).toMatchObject({ network: "base-sepolia", sourceCommit: null, runEvents: {} });
  });

  it.each([
    ["not an object", []],
    ["a future version", { ...v1, schemaVersion: 2 }],
    ["no capturedAt", { ...v1, capturedAt: "yesterday" }],
    ["no environment", { ...v1, environment: " " }],
    ["no responses", { ...v1, responses: null }],
  ])("rejects %s", (_name, json) => {
    expect(() => normalizeCapture(json)).toThrow();
  });
});

describe("replay timing", () => {
  it("clamps gaps to 50 ms - 2 s", () => {
    expect(clampGap(0)).toBe(MIN_GAP_MS);
    expect(clampGap(10)).toBe(MIN_GAP_MS);
    expect(clampGap(700)).toBe(700);
    expect(clampGap(60_000)).toBe(MAX_GAP_MS);
  });

  it("derives delays from the original occurredAt gaps", () => {
    const events: RunEvent[] = normalizeCapture(v1).runEvents[RUN] ?? [];
    expect(replayDelays(events)).toEqual([MIN_GAP_MS, MIN_GAP_MS, MAX_GAP_MS]);
  });
});

describe("replay source in a replay build", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.stubEnv("VITE_DEMO_MODE", "replay");
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        if (url === "/demo/capture.json") {
          return Promise.resolve(new Response(JSON.stringify(v1), { status: 200 }));
        }
        return Promise.reject(new Error(`unexpected request to ${url}`));
      }),
    );
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.useRealTimers();
    resetCaptureForTests(null);
  });

  it("answers reads from the recording and never calls the API", async () => {
    const { apiGet } = await import("@/lib/api/source");
    await expect(apiGet("/api/v1/ping")).resolves.toEqual({ service: "orchestrator" });
    // exact path+query first, then the bare path (the day in the query changes every day)
    await expect(apiGet("/api/v1/spend?day=2099-01-01")).resolves.toEqual({ day: "2026-10-01" });
    const urls = vi.mocked(fetch).mock.calls.map(([url]) => url as unknown);
    expect(urls.every((url) => url === "/demo/capture.json")).toBe(true);
  });

  it("reports a path that is not in the recording", async () => {
    const { apiGet } = await import("@/lib/api/source");
    await expect(apiGet("/api/v1/ledger/revenue")).rejects.toMatchObject({
      kind: "not-found",
      detail: "This is not in this recording.",
    });
  });

  it("serves the run event export from runEvents", async () => {
    const { apiGet } = await import("@/lib/api/source");
    const events = await apiGet<RunEvent[]>(`/api/v1/runs/${RUN}/events`);
    expect(events.map((e) => e.seq)).toEqual([1, 2, 3]);
  });

  it("answers /me as a reader and refuses mutations", async () => {
    const { apiGet, apiPost } = await import("@/lib/api/source");
    await expect(apiGet("/api/v1/me")).resolves.toEqual({
      name: "recorded-demo",
      roles: ["READER"],
    });
    await expect(apiPost("/api/v1/runs", { question: "q" })).rejects.toMatchObject({
      kind: "replay",
    });
  });

  it("replays events with clamped original gaps, then closes", async () => {
    const { subscribeRunEvents } = await import("@/lib/api/source");
    const seen: number[] = [];
    const onClose = vi.fn();
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    subscribeRunEvents(RUN, (e) => seen.push(e.seq), { onClose });
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(MIN_GAP_MS);
    expect(seen).toEqual([1]);
    await vi.advanceTimersByTimeAsync(MIN_GAP_MS);
    expect(seen).toEqual([1, 2]);
    await vi.advanceTimersByTimeAsync(MAX_GAP_MS - 1);
    expect(seen).toEqual([1, 2]);
    await vi.advanceTimersByTimeAsync(1);
    expect(seen).toEqual([1, 2, 3]);
    await vi.advanceTimersByTimeAsync(0);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("has no token and no pre-flight 401 stop in a recording", async () => {
    const { authHeaders } = await import("@/lib/auth/token-store");
    expect(authHeaders()).toEqual({});
  });

  it("builds the banner text from the capture", async () => {
    const { bannerText } = await import("@/lib/banner-text");
    expect(bannerText(normalizeCapture(v1))).toBe(
      "Recorded on AWS eu-central-1, 2026-10-01 - Base Sepolia testnet. Nothing on this page is live.",
    );
  });
});
