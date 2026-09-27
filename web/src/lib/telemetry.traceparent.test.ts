import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const TRACEPARENT_PATTERN = /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/;

/**
 * jsdom does not implement the Resource Timing / PerformanceObserver APIs that
 * @opentelemetry/instrumentation-fetch uses to enrich spans after the fact. It feature-detects
 * PerformanceObserver once at module load time to decide whether it's running in a browser at
 * all, so the stub must exist before `telemetry.ts` (and transitively instrumentation-fetch) is
 * imported. The `getEntriesByType`-less path is already handled gracefully by the library.
 */
class StubPerformanceObserver {
  observe(): void {
    // no-op: nothing collects resource timing entries in tests.
  }
  disconnect(): void {
    // no-op
  }
}

describe("telemetry: browser fetch traceparent propagation", () => {
  beforeEach(() => {
    vi.stubGlobal("PerformanceObserver", StubPerformanceObserver);
    vi.stubEnv("VITE_OTEL_ENABLED", "true");
    vi.stubEnv("VITE_OTEL_TRACES_URL", "/otlp/v1/traces");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("adds a W3C traceparent header to same-origin API fetches", async () => {
    const stubFetch = vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      ),
    );
    vi.stubGlobal("fetch", stubFetch);

    // Imported dynamically, after the stubs above, so instrumentation-fetch's
    // module-scope browser feature detection sees them.
    const { initTelemetry } = await import("@/lib/telemetry");
    initTelemetry();

    await fetch("/api/v1/ping");

    const pingCall = stubFetch.mock.calls.find(
      ([input]) => typeof input === "string" && input.includes("/api/v1/ping"),
    );
    expect(pingCall).toBeDefined();

    const [, init] = pingCall ?? [];
    const headers = new Headers(init?.headers);
    const traceparent = headers.get("traceparent");
    expect(traceparent).toMatch(TRACEPARENT_PATTERN);
  });

  it("does not patch fetch or add a traceparent header when VITE_OTEL_ENABLED is 'false'", async () => {
    vi.stubEnv("VITE_OTEL_ENABLED", "false");

    const stubFetch = vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      ),
    );
    vi.stubGlobal("fetch", stubFetch);

    const { initTelemetry } = await import("@/lib/telemetry");
    initTelemetry();

    await fetch("/api/v1/ping");

    expect(stubFetch).toHaveBeenCalledTimes(1);
    const [, init] = stubFetch.mock.calls[0] ?? [];
    const headers = new Headers(init?.headers);
    expect(headers.has("traceparent")).toBe(false);
  });

  it("does not add a traceparent header to requests matching ignoreUrls (the OTLP exporter itself)", async () => {
    const stubFetch = vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response("{}", { status: 200, headers: { "content-type": "application/json" } }),
      ),
    );
    vi.stubGlobal("fetch", stubFetch);

    const { initTelemetry } = await import("@/lib/telemetry");
    initTelemetry();

    await fetch("/otlp/v1/traces");

    const otlpCall = stubFetch.mock.calls.find(
      ([input]) => typeof input === "string" && input.includes("/otlp/v1/traces"),
    );
    expect(otlpCall).toBeDefined();

    const [, init] = otlpCall ?? [];
    const headers = new Headers(init?.headers);
    expect(headers.has("traceparent")).toBe(false);
  });
});
