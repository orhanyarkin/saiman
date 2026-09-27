import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { registerInstrumentations } from "@opentelemetry/instrumentation";
import { FetchInstrumentation } from "@opentelemetry/instrumentation-fetch";
import { resourceFromAttributes } from "@opentelemetry/resources";
import { BatchSpanProcessor, WebTracerProvider } from "@opentelemetry/sdk-trace-web";

let initialised = false;

/** Escapes regex metacharacters so a URL string can be used as a literal-match pattern. */
function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Initialises browser tracing so that fetch calls to same-origin APIs (proxied to the
 * orchestrator in dev, see vite.config.ts) carry a W3C `traceparent` header and are exported
 * as spans over OTLP/HTTP to the local collector.
 *
 * Gated behind VITE_OTEL_ENABLED, which is unset/`false` in replay builds (ADR-0004) — in that
 * case this is a no-op. Must run before the app renders so the fetch patch is in place for the
 * first request. Idempotent: only the first call does anything. See ADR-0006.
 */
export function initTelemetry(): void {
  if (initialised || import.meta.env.VITE_OTEL_ENABLED !== "true") {
    return;
  }

  const tracesUrl = import.meta.env.VITE_OTEL_TRACES_URL;
  if (!tracesUrl) {
    console.warn(
      "VITE_OTEL_ENABLED is true but VITE_OTEL_TRACES_URL is not set; skipping telemetry init.",
    );
    return;
  }

  initialised = true;

  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({ "service.name": "saiman-web" }),
    spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url: tracesUrl }))],
  });

  // WebTracerProvider registers a StackContextManager and the default W3C trace-context
  // propagator; no ZoneContextManager needed since the fetch span is the trace root in M0.
  provider.register();

  registerInstrumentations({
    instrumentations: [
      new FetchInstrumentation({
        // Never trace calls to the collector itself (would recurse). Derived from the
        // configured exporter URL, rather than hardcoded, so a differently-pathed endpoint
        // (e.g. a future live deployment) is still excluded correctly.
        ignoreUrls: [new RegExp(escapeForRegExp(tracesUrl))],
      }),
    ],
  });
}
