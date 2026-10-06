// @vitest-environment node
import type { AddressInfo } from "node:net";

import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { createFixtureServer } from "./fixture-server.ts";

const POST_HEADERS = { "Content-Type": "application/json", "X-Saiman-Csrf": "1" };

let base = "";
const server = createFixtureServer({ stepMs: 5, heartbeatMs: 60_000, auth: false });

beforeAll(async () => {
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  base = `http://127.0.0.1:${String((server.address() as AddressInfo).port)}`;
});
afterAll(() => {
  server.closeAllConnections();
  server.close();
});

async function post(path: string, body: unknown, headers: Record<string, string> = POST_HEADERS) {
  return fetch(base + path, { method: "POST", headers, body: JSON.stringify(body) });
}

interface Parsed {
  id: string;
  event: string;
  data: { type: string; seq: number; data: Record<string, unknown> };
}

/** Reads an SSE response to its end into id/event/data frames. */
async function readSse(response: Response): Promise<{ text: string; frames: Parsed[] }> {
  const text = await response.text();
  const frames = text
    .split("\n\n")
    .filter((block) => block.includes("event:"))
    .map((block) => {
      const field = (name: string) =>
        block
          .split("\n")
          .find((line) => line.startsWith(`${name}:`))
          ?.slice(name.length + 1)
          .trim() ?? "";
      return {
        id: field("id"),
        event: field("event"),
        data: JSON.parse(field("data")) as Parsed["data"],
      };
    });
  return { text, frames };
}

async function start() {
  const response = await post("/api/v1/runs", {
    question: "THYAO son açıklamalar?",
    budgetAtomic: 50000,
  });
  expect(response.status).toBe(202);
  return (await response.json()) as { runId: string; eventsUrl: string };
}

async function waitForStatus(runId: string, status: string) {
  for (let i = 0; i < 100; i++) {
    const summary = (await (await fetch(`${base}/api/v1/runs/${runId}`)).json()) as {
      status: string;
    };
    if (summary.status === status) {
      return;
    }
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error(`run never reached ${status}`);
}

describe("fixture server", () => {
  it("answers ping and rejects POST without the guard headers", async () => {
    expect((await fetch(`${base}/api/v1/ping`)).status).toBe(200);
    expect(
      (await post("/api/v1/runs", { question: "abc" }, { "Content-Type": "application/json" }))
        .status,
    ).toBe(403);
    expect((await post("/api/v1/runs", { question: "abc" }, { "X-Saiman-Csrf": "1" })).status).toBe(
      403,
    );
  });

  it("validates the question", async () => {
    const response = await post("/api/v1/runs", { question: "a" });
    expect(response.status).toBe(400);
    expect(response.headers.get("content-type")).toContain("problem+json");
  });

  it("runs the script, pauses for approval, resumes after Last-Event-ID and ends with 204", async () => {
    const { runId } = await start();
    await waitForStatus(runId, "AWAITING_APPROVAL");

    const paused = await fetch(`${base}/api/v1/runs/${runId}/events`, {
      headers: { Accept: "text/event-stream" },
      signal: AbortSignal.timeout(300),
    }).then(async (response) => {
      const reader = response.body?.getReader();
      const first = await reader?.read();
      await reader?.cancel();
      return new TextDecoder().decode(first?.value);
    });
    expect(paused).toContain("retry: 1000");
    expect(paused).toContain("event: PAYMENT_APPROVAL_REQUIRED");
    expect(paused).not.toContain("PAYMENT_SETTLED");

    const summary = (await (await fetch(`${base}/api/v1/runs/${runId}`)).json()) as {
      reserved: { atomicUnits: number };
    };
    expect(summary.reserved.atomicUnits).toBe(20000);

    const approval = (/"approvalId":"([^"]+)"/.exec(paused) ?? [])[1] ?? "";
    const decided = await post(`/api/v1/runs/${runId}/approvals/${approval}`, {
      decision: "APPROVE",
    });
    expect(decided.status).toBe(200);
    expect(
      (await post(`/api/v1/runs/${runId}/approvals/${approval}`, { decision: "APPROVE" })).status,
    ).toBe(409);

    await waitForStatus(runId, "SUCCEEDED");
    const { frames } = await readSse(
      await fetch(`${base}/api/v1/runs/${runId}/events`, {
        headers: { Accept: "text/event-stream" },
      }),
    );
    expect(frames.map((f) => f.data.seq)).toEqual(frames.map((_, i) => i + 1));
    expect(frames.at(-1)?.event).toBe("RUN_COMPLETED");
    expect(frames.some((f) => f.event === "PAYMENT_SETTLED")).toBe(true);

    const resumed = await readSse(
      await fetch(`${base}/api/v1/runs/${runId}/events`, {
        headers: { Accept: "text/event-stream", "Last-Event-ID": String(frames.length - 1) },
      }),
    );
    expect(resumed.frames.map((f) => f.event)).toEqual(["RUN_COMPLETED"]);

    const done = await fetch(`${base}/api/v1/runs/${runId}/events`, {
      headers: { Accept: "text/event-stream", "Last-Event-ID": String(frames.length) },
    });
    expect(done.status).toBe(204);

    const exported = (await (
      await fetch(`${base}/api/v1/runs/${runId}/events`, {
        headers: { Accept: "application/json" },
      })
    ).json()) as unknown[];
    expect(exported).toHaveLength(frames.length);
  });

  it("fails the run when the approval is rejected", async () => {
    const { runId } = await start();
    await waitForStatus(runId, "AWAITING_APPROVAL");
    const events = (await (
      await fetch(`${base}/api/v1/runs/${runId}/events`, {
        headers: { Accept: "application/json" },
      })
    ).json()) as { type: string; data: { approvalId?: string } }[];
    const approval =
      events.find((e) => e.type === "PAYMENT_APPROVAL_REQUIRED")?.data.approvalId ?? "";
    expect(
      (await post(`/api/v1/runs/${runId}/approvals/${approval}`, { decision: "REJECT" })).status,
    ).toBe(200);
    await waitForStatus(runId, "FAILED");
  });

  it("serves captured responses verbatim and 404s unknown paths", async () => {
    const custom = createFixtureServer({
      auth: false,
      capture: { responses: { "/api/v1/spend?day=2026-10-01": { dailyCap: 1 } } },
    });
    await new Promise<void>((done) => custom.listen(0, "127.0.0.1", done));
    const url = `http://127.0.0.1:${String((custom.address() as AddressInfo).port)}`;
    expect(await (await fetch(`${url}/api/v1/spend?day=2026-10-01`)).json()).toEqual({
      dailyCap: 1,
    });
    expect((await fetch(`${url}/api/v1/nope`)).status).toBe(404);
    custom.close();
  });

  it("derives payment intents and pending approvals from a started run", async () => {
    const { runId } = await start();
    let pending: { id: string; runId: string; amountAtomic: number }[] = [];
    for (let i = 0; i < 100 && pending.length === 0; i++) {
      pending = (await (
        await fetch(`${base}/api/v1/approvals?status=PENDING`)
      ).json()) as typeof pending;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    const mine = pending.find((a) => a.runId === runId);
    expect(mine?.amountAtomic).toBe(20000);
    const payments = (await (await fetch(`${base}/api/v1/runs/${runId}/payments`)).json()) as {
      items: { status: string }[];
    };
    expect(payments.items.map((i) => i.status)).toEqual(["AWAITING_APPROVAL"]);
    expect((await fetch(`${base}/api/v1/runs/nope/payments`)).status).toBe(404);
  });

  it("shows a settled payment in the ledger a moment after the settled event", async () => {
    const lagged = createFixtureServer({
      stepMs: 5,
      heartbeatMs: 60_000,
      ledgerLagMs: 150,
      auth: false,
    });
    await new Promise<void>((done) => lagged.listen(0, "127.0.0.1", done));
    const url = `http://127.0.0.1:${String((lagged.address() as AddressInfo).port)}`;
    const headers = { "Content-Type": "application/json", "X-Saiman-Csrf": "1" };
    const { runId } = (await (
      await fetch(`${url}/api/v1/runs`, {
        method: "POST",
        headers,
        body: JSON.stringify({ question: "THYAO son açıklamalar?" }),
      })
    ).json()) as { runId: string };
    let approval = "";
    for (let i = 0; i < 100 && approval === ""; i++) {
      const list = (await (await fetch(`${url}/api/v1/approvals?status=PENDING`)).json()) as {
        id: string;
        runId: string;
      }[];
      approval = list.find((a) => a.runId === runId)?.id ?? "";
      await new Promise((r) => setTimeout(r, 10));
    }
    await fetch(`${url}/api/v1/runs/${runId}/approvals/${approval}`, {
      method: "POST",
      headers,
      body: JSON.stringify({ decision: "APPROVE" }),
    });
    const poll = async () =>
      (await (await fetch(`${url}/api/v1/ledger/payments?runId=${runId}&limit=50`)).json()) as {
        items: { paymentId: string; buyerState: string }[];
      };
    expect((await poll()).items).toHaveLength(0); // not consumed yet
    let items: { paymentId: string; buyerState: string }[] = [];
    for (let i = 0; i < 100 && items.length === 0; i++) {
      await new Promise((r) => setTimeout(r, 20));
      items = (await poll()).items;
    }
    expect(items[0]?.buyerState).toBe("SETTLED");
    const detail = (await (
      await fetch(`${url}/api/v1/ledger/payments/${items[0]?.paymentId ?? ""}`)
    ).json()) as { entries: { kind: string }[] };
    expect(detail.entries.map((e) => e.kind)).toEqual(["ENCUMBER", "SETTLE", "SALE"]);
    lagged.closeAllConnections();
    lagged.close();
  });

  it("scripts the reconciliation start: 202, 409 while running, 429 right after, 202 again", async () => {
    const recon = createFixtureServer({ auth: false, reconRunMs: 100, reconCooldownMs: 1500 });
    await new Promise<void>((done) => recon.listen(0, "127.0.0.1", done));
    const url = `http://127.0.0.1:${String((recon.address() as AddressInfo).port)}`;
    const start = () =>
      fetch(`${url}/api/v1/reconciliation/runs`, {
        method: "POST",
        headers: POST_HEADERS,
        body: "{}",
      });
    const first = await start();
    expect(first.status).toBe(202);
    const { runId } = (await first.json()) as { runId: string };
    expect((await start()).status).toBe(409);
    const running = (await (await fetch(`${url}/api/v1/reconciliation/runs/latest`)).json()) as {
      runId: string;
      status: string;
    };
    expect(running).toMatchObject({ runId, status: "RUNNING" });
    await new Promise((r) => setTimeout(r, 150));
    const limited = await start();
    expect(limited.status).toBe(429);
    expect(limited.headers.get("retry-after")).toMatch(/^\d+$/);
    await new Promise((r) => setTimeout(r, 1500));
    expect((await start()).status).toBe(202);
    recon.closeAllConnections();
    recon.close();
  });
});
