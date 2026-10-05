/**
 * Dev and preview proxy table (ADR-0022). The browser only ever talks to its own origin; Vite
 * routes `/api` to the right backend. `changeOrigin` stays false on purpose: the backends'
 * Host guard allows `localhost:<any port>`, and the original Host must reach them unchanged.
 *
 * ORDER MATTERS: Vite tries the keys in insertion order and the first match wins, so the ledger's
 * regex keys must come before the catch-all `/api` (orchestrator). `proxy.test.ts` pins this.
 */
import type { ProxyOptions } from "vite";

export const ORCHESTRATOR = "http://localhost:8080";
export const LEDGER = "http://localhost:8082";
export const COLLECTOR = "http://localhost:4318";

/** Paths owned by the ledger service. Everything else under /api belongs to the orchestrator. */
export const LEDGER_PATHS = "^/api/v1/(ledger|reconciliation)/";

export function buildProxy(apiOverride?: string): Record<string, ProxyOptions> {
  const proxy: Record<string, ProxyOptions> = {};
  if (apiOverride) {
    // Fixture mode (e2e): one fake backend answers every /api path.
    proxy["^/api/"] = { target: apiOverride, changeOrigin: false };
  } else {
    proxy[LEDGER_PATHS] = { target: LEDGER, changeOrigin: false };
    proxy["^/api/"] = { target: ORCHESTRATOR, changeOrigin: false };
  }
  proxy["^/otlp/"] = {
    target: COLLECTOR,
    changeOrigin: false,
    rewrite: (path) => path.replace(/^\/otlp/, ""),
  };
  return proxy;
}
