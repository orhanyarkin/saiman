import { describe, expect, it } from "vitest";

import { buildProxy, LEDGER, ORCHESTRATOR } from "./proxy.ts";

/** Mirrors Vite: keys are tried in insertion order, the first regex that matches wins. */
function route(path: string, proxy = buildProxy()): string | undefined {
  for (const [key, options] of Object.entries(proxy)) {
    if (new RegExp(key).test(path)) {
      return typeof options.target === "string" ? options.target : undefined;
    }
  }
  return undefined;
}

describe("proxy table", () => {
  it.each([
    ["/api/v1/ledger/trial-balance", LEDGER],
    ["/api/v1/ledger/payments?runId=1", LEDGER],
    ["/api/v1/reconciliation/runs/latest", LEDGER],
    ["/api/v1/reconciliation/runs", LEDGER],
    ["/api/v1/ping", ORCHESTRATOR],
    ["/api/v1/runs/abc/events", ORCHESTRATOR],
    ["/api/v1/approvals?status=PENDING", ORCHESTRATOR],
    ["/api/v1/spend", ORCHESTRATOR],
    // Prefix lookalikes must not leak to the ledger.
    ["/api/v1/ledgerx/foo", ORCHESTRATOR],
    ["/api/v1/runs/ledger/x", ORCHESTRATOR],
  ])("routes %s to %s", (path, target) => {
    expect(route(path)).toBe(target);
  });

  it("puts the ledger regex before the catch-all /api key", () => {
    const keys = Object.keys(buildProxy());
    expect(keys.indexOf("^/api/v1/(ledger|reconciliation)/")).toBeLessThan(keys.indexOf("^/api/"));
  });

  it("never rewrites Host and sends telemetry to the collector", () => {
    const proxy = buildProxy();
    for (const options of Object.values(proxy)) {
      expect(options.changeOrigin).toBe(false);
    }
    expect(route("/otlp/v1/traces")).toBe("http://localhost:4318");
    expect(proxy["^/otlp/"]?.rewrite?.("/otlp/v1/traces")).toBe("/v1/traces");
  });

  it("sends every /api path to the fixture server in fixture mode", () => {
    const proxy = buildProxy("http://localhost:4010");
    expect(route("/api/v1/ledger/trial-balance", proxy)).toBe("http://localhost:4010");
    expect(route("/api/v1/ping", proxy)).toBe("http://localhost:4010");
  });
});
