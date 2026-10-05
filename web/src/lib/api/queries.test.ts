import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  pendingApprovalsQuery,
  runListQuery,
  runPaymentsQuery,
  spendQuery,
} from "@/lib/api/queries";
import type { RunListItem, RunPayments } from "@/lib/api/types";

const money = (atomicUnits: number, asset = "USDC") => ({ atomicUnits, asset, decimals: 6 });

function runItem(runId: string): RunListItem {
  return {
    runId,
    status: "SUCCEEDED",
    question: "q",
    budget: money(50000),
    committed: money(0),
    reserved: money(0),
    cost: { paymentsUsdc: money(0), llmUsd: money(0, "USD"), totalUsd: money(0, "USD") },
    createdAt: "2026-10-01T10:00:00Z",
    pendingApprovals: 0,
  };
}

function ok(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200 });
}

describe("queries", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("run list pages by the opaque `next` cursor until it is absent", async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(ok({ items: [runItem("a")], next: "cursor 2/=" }))
      .mockResolvedValueOnce(ok({ items: [runItem("b")] }));
    const client = new QueryClient();
    const options = runListQuery(20);

    await client.infiniteQuery({ ...options, pages: 2 });

    const urls = vi.mocked(fetch).mock.calls.map((call) => call[0] as string);
    expect(urls).toEqual(["/api/v1/runs?limit=20", "/api/v1/runs?limit=20&before=cursor+2%2F%3D"]);
    const data = client.getQueryData(options.queryKey);
    expect(data?.pages.flatMap((page) => page.items.map((item) => item.runId))).toEqual(["a", "b"]);
    expect(options.getNextPageParam({ items: [] }, [], null, [])).toBeNull();
    expect(options.getNextPageParam({ items: [], next: null }, [], null, [])).toBeNull();
    expect(options.getNextPageParam({ items: [], next: "x" }, [], null, [])).toBe("x");
  });

  it("asks for the pending approvals and one spend day", async () => {
    vi.mocked(fetch).mockImplementation(() => Promise.resolve(ok([])));
    const client = new QueryClient();
    await client.query(pendingApprovalsQuery());
    await client.query(spendQuery("2026-10-05"));
    const urls = vi.mocked(fetch).mock.calls.map((call) => call[0] as string);
    expect(urls).toEqual(["/api/v1/approvals?status=PENDING", "/api/v1/spend?day=2026-10-05"]);
  });

  it("polls approvals every 5 s", () => {
    expect(pendingApprovalsQuery().refetchInterval).toBe(5000);
  });

  describe("payments polling", () => {
    const item = (status: "SETTLED" | "HELD") => ({
      paymentIntentId: "p",
      tool: "t",
      resource: "r",
      status,
      createdAt: "2026-10-01T10:00:00Z",
      updatedAt: "2026-10-01T10:00:00Z",
    });
    const interval = (terminal: boolean, items: RunPayments["items"] | undefined) => {
      const client = new QueryClient();
      const options = runPaymentsQuery("run-1", terminal);
      if (items) {
        client.setQueryData(options.queryKey, { items });
      }
      const query = client.getQueryCache().find({ queryKey: options.queryKey });
      const refetchInterval = options.refetchInterval as unknown as (q: unknown) => unknown;
      return query ? refetchInterval(query) : "no query";
    };

    it("polls every 10 s while the run is live", () => {
      expect(interval(false, [item("SETTLED")])).toBe(10_000);
    });

    it("keeps polling a finished run while an intent is HELD", () => {
      expect(interval(true, [item("SETTLED"), item("HELD")])).toBe(10_000);
    });

    it("stops once the run is over and nothing is HELD", () => {
      expect(interval(true, [item("SETTLED")])).toBe(false);
    });
  });
});
