# ADR-0006: Tracing with Micrometer + OpenTelemetry, viewed locally in Jaeger behind an OTel Collector

Status: Accepted (2026-09-27)

## Context
M0 must show one trace spanning web → orchestrator → Postgres locally, and later milestones record tokens and USD on every LLM and payment path. ADR-0004 sends telemetry to Grafana Cloud (free tier) during AWS sessions. The local stack runs in WSL2 next to Postgres, Redpanda, Valkey and four Spring services, so the viewer must be light. We need to decide how Java services and the browser produce spans, how context propagates, and what stores and shows traces locally.

## Decision
- **Java**: Spring Boot's `spring-boot-starter-opentelemetry` (Micrometer Observation API → Micrometer Tracing OTel bridge → OTLP/HTTP exporter). JDBC spans come from `datasource-micrometer-spring-boot` (an Observation-based DataSource proxy). No OpenTelemetry Java agent and no OTel instrumentation Spring starter.
- **Propagation**: W3C Trace Context (`traceparent`, `tracestate`) end to end. Parent-based sampling, so the caller's decision wins; 100% sampling locally.
- **Browser**: OpenTelemetry JS web SDK with fetch instrumentation and the OTLP/HTTP exporter, enabled by `VITE_OTEL_ENABLED` and off in replay builds. In development the Vite server proxies `/api` and `/otlp`, so browser, API and collector are same-origin and need no CORS. Cross-origin CORS (allowing `traceparent`/`tracestate`) is configured when the SPA and APIs are deployed separately.
- **Pipeline**: every service and the browser send to one OpenTelemetry Collector (core distribution, pinned). Locally it forwards traces to Jaeger v2 all-in-one (in-memory storage, UI on 127.0.0.1:16686) and metrics to the `debug` exporter. During AWS sessions the same collector gets an `otlphttp` exporter to Grafana Cloud; services and the SPA don't change.

## Alternatives
- Grafana `otel-lgtm` image: the closest match to Grafana Cloud (Grafana + Tempo + Prometheus + Loki) and shows metrics too, but it is ~0.9 GB compressed and runs five backends in one container. Kept as an opt-in compose profile for when cost-metric dashboards are needed.
- Zipkin: smaller than LGTM but a JVM process with a dated UI and no first-class OTLP path in the default image.
- Collector `debug` exporter only: no extra container, but traces are log lines — not demonstrable and not scriptable.
- Jaeger alone as the pipeline (Jaeger v2 is built on the Collector framework): one fewer container, but it merges the "viewer" and "egress" roles, so moving to Grafana Cloud would mean reconfiguring the viewer.
- OpenTelemetry Java agent: zero-code and broad, but a bytecode agent that duplicates Micrometer observations, costs startup time and memory, and is harder to explain as idiomatic Spring.

## Consequences
+ Small local footprint (~55 MB image, in-memory store) and a stable query API (`/api/v3/traces/{id}`) that a script can use to verify traces.
+ The collector is the only component that knows where telemetry goes; Grafana Cloud is a config change.
+ Spans from HTTP, JDBC and (later) Kafka, LLM and payment code all use the same Observation API.
− No local metrics UI until the optional `otel-lgtm` profile is added.
− Local traces are lost on restart (acceptable for development).
− Browser tracing adds a few dependencies to live builds; replay builds disable it.
Revisit if: local metric dashboards become necessary, or Jaeger's in-memory store becomes a bottleneck.

## Amendment (2026-09-28): payment data is redacted at the source
The core collector distribution cannot redact span attributes. Instead of building a custom collector with the contrib `redaction` processor, payment data never leaves the service:
- Boot's HTTP server and client observations do not record request or response headers, so `PAYMENT-SIGNATURE`, `PAYMENT-REQUIRED` and `PAYMENT-RESPONSE` are not captured by default. No service may add header capture.
- The x402 starter emits only allowlisted key-values: low cardinality `x402.network`, `x402.scheme`, `x402.asset`, `x402.outcome`; high cardinality `x402.payer` and `x402.tx_hash` (both public on chain). It also registers an `ObservationFilter` that drops any key whose name matches a payment header or contains `signature`, `payload` or `authorization`.
- Exception messages and Problem Details never include payloads or signatures.
- Tests in the starter and seller-api export spans in memory and assert that no attribute contains the encoded payload or the signature.
Revisit when Grafana Cloud egress starts (M6): the contrib `redaction` processor may be added as a second layer.
