---
name: boot4-observability
description: Spring Boot 4 tracing/OTLP facts behind ADR-0006
metadata:
  type: project
---
- Boot 4 exports OTLP over HTTP via `management.opentelemetry.tracing.export.otlp.endpoint` (port 4318, path `/v1/traces`). Boot 4.1's `OpenTelemetryEnvironmentVariableEnvironmentPostProcessor` also maps the standard `OTEL_*` env vars (verified by disassembling the 4.1.1 jar, T0): `OTEL_EXPORTER_OTLP_ENDPOINT` is a base URL and Boot appends `v1/traces`/`v1/metrics`/`v1/logs`. Use `OTEL_EXPORTER_OTLP_ENDPOINT=http://<collector>:4318`; the old `...:4317` value was the gRPC port.
- Tracing is a no-op in `@SpringBootTest` by default; opt in with `@AutoConfigureTracing` (replaced `@AutoConfigureObservability`).
- Boot 4.1 has no built-in JDBC observation; JDBC spans come from `net.ttddyy.observation:datasource-micrometer-spring-boot` 2.x.
- Boot 4 modular starters: `spring-boot-starter-webmvc` (not `-web`), Flyway needs `spring-boot-starter-flyway`.
- Local viewer: Jaeger v2 behind the OTel Collector (ADR-0006); verify traces via Jaeger `/api/v3/traces/{id}`.

**Why:** easy to get wrong from Boot 3 habits.
**How to apply:** use these names in configs and tests; related [[toolchain-gotchas]].
