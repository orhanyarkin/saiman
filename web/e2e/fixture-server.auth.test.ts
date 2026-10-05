// @vitest-environment node
import type { AddressInfo } from "node:net";

import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { CAP_MARKER, createFixtureServer } from "./fixture-server.ts";

const READER = "fixture-reader-0123456789abcdef0123456789abcdef";
const OPERATOR = "fixture-operator-0123456789abcdef0123456789abcdef";
const POST = { "Content-Type": "application/json", "X-Saiman-Csrf": "1" };

let base = "";
let openBase = "";
const server = createFixtureServer({ stepMs: 5, heartbeatMs: 60_000 });
const open = createFixtureServer({ auth: false });

beforeAll(async () => {
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  await new Promise<void>((done) => open.listen(0, "127.0.0.1", done));
  base = `http://127.0.0.1:${String((server.address() as AddressInfo).port)}`;
  openBase = `http://127.0.0.1:${String((open.address() as AddressInfo).port)}`;
});
afterAll(() => {
  for (const s of [server, open]) {
    s.closeAllConnections();
    s.close();
  }
});

const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

describe("fixture server authentication", () => {
  it("answers 401 with WWW-Authenticate without or with a wrong token", async () => {
    const none = await fetch(`${base}/api/v1/runs?limit=5`);
    expect(none.status).toBe(401);
    expect(none.headers.get("www-authenticate")).toBe("Bearer");
    const wrong = await fetch(`${base}/api/v1/runs?limit=5`, { headers: bearer("nope") });
    expect(wrong.status).toBe(401);
  });

  it("serves GETs to a reader and an operator, and says who they are on /me", async () => {
    expect((await fetch(`${base}/api/v1/ping`, { headers: bearer(READER) })).status).toBe(200);
    const reader = await fetch(`${base}/api/v1/me`, { headers: bearer(READER) });
    expect(((await reader.json()) as { roles: string[] }).roles).toEqual(["READER"]);
    const operator = await fetch(`${base}/api/v1/me`, { headers: bearer(OPERATOR) });
    expect(((await operator.json()) as { roles: string[] }).roles).toEqual(["OPERATOR"]);
  });

  it("refuses a reader's POST with 403 and lets an operator start a run", async () => {
    const body = JSON.stringify({ question: "THYAO son açıklamalar?" });
    const denied = await fetch(`${base}/api/v1/runs`, {
      method: "POST",
      headers: { ...POST, ...bearer(READER) },
      body,
    });
    expect(denied.status).toBe(403);
    const ok = await fetch(`${base}/api/v1/runs`, {
      method: "POST",
      headers: { ...POST, ...bearer(OPERATOR) },
      body,
    });
    expect(ok.status).toBe(202);
  });

  it("rejects a token in the URL", async () => {
    const response = await fetch(`${base}/api/v1/ping?access_token=${READER}`, {
      headers: bearer(READER),
    });
    expect(response.status).toBe(400);
  });

  it("protects the event stream too", async () => {
    expect(
      (
        await fetch(`${base}/api/v1/runs/x/events`, {
          headers: { Accept: "text/event-stream" },
        })
      ).status,
    ).toBe(401);
  });

  it("answers the daily-cap problem for the cap marker only", async () => {
    const response = await fetch(`${base}/api/v1/runs`, {
      method: "POST",
      headers: { ...POST, ...bearer(OPERATOR) },
      body: JSON.stringify({ question: `${CAP_MARKER} THYAO` }),
    });
    expect(response.status).toBe(503);
    expect(await response.json()).toMatchObject({
      type: "urn:saiman:problem:llm-daily-cap",
      code: "LLM_DAILY_CAP_REACHED",
      replayAvailable: true,
    });
  });

  it("auth-off mode is open and /me says operator", async () => {
    expect((await fetch(`${openBase}/api/v1/ping`)).status).toBe(200);
    const me = await fetch(`${openBase}/api/v1/me`);
    expect(((await me.json()) as { roles: string[] }).roles).toEqual(["OPERATOR"]);
  });
});
