---
name: boot4-review-checklist
description: Recurring Spring Boot 4.1 pitfalls seen in Saiman reviews (test HTTP clients, OTLP metrics default endpoint, auto-config registration tests) with jar-verified facts
metadata:
  type: project
---

Facts verified against Boot 4.1.1 jars in ~/.gradle/caches on 2026-09-27 (M0 T3 review):

- **Test HTTP client drift.** `spring-boot-starter-test` does NOT bring `spring-boot-resttestclient`;
  `spring-boot-starter-webmvc-test` does. That module has both `TestRestTemplate` (needs
  `spring-boot-restclient` on the test classpath, BOM-managed, not a declared dependency, so
  `ClassNotFoundException: RestTemplateBuilder` without it) and `RestTestClient` +
  `@AutoConfigureRestTestClient` (works without extra deps). In M0, ingest used JDK HttpClient and
  seller-api/ledger used `RestClient.create` + `@LocalServerPort`. Flag divergence; the idiomatic
  Boot 4 choice is `RestTestClient` via the existing `spring-boot-starter-webmvc-test` alias.
- **OTLP metrics are on by default.** `spring-boot-starter-opentelemetry` pulls
  `micrometer-registry-otlp`; `management.otlp.metrics.export.enabled` defaults true and the URL
  falls back to Micrometer's `http://localhost:4318/v1/metrics` (step 1m). Leaving the *trace*
  endpoint unset does not silence metrics. Claims of "bootRun is quiet" are wrong when no collector
  runs. Correction (M0 T4 review): the ingest and evals `@SpringBootTest` logs DO show
  "Publishing metrics for OtlpMeterRegistry every 1m to http://localhost:4318/v1/metrics", so
  export is not disabled in tests on 4.1.1. Fix is cross-cutting: route to the orchestrator.
- **Auto-config tests.** `ApplicationContextRunner` + `AutoConfigurations.of(X.class)` does not prove
  the `AutoConfiguration.imports` file is correct. Ask for an
  `ImportCandidates.load(AutoConfiguration.class, cl)` assertion
  (`org.springframework.boot.context.annotation.ImportCandidates`, in `spring-boot` jar).
- Property names: tracing endpoint is `management.opentelemetry.tracing.export.otlp.endpoint`
  (old `management.otlp.tracing.*` is error-level deprecated since 4.0.0).
- Spring Boot 4.0.0 GA was Nov 2025; agent memories dating "Boot 4 GA" to 2026-08-20 confuse it
  with the 4.1.1 pin.

**How to apply:** check these on every new service/test/starter diff; re-verify against jars if the
Boot version in `gradle/libs.versions.toml` changes.

Added 2026-09-27 (M0 T2 orchestrator review), verified against 4.1.1 sources / TC 2.0.5 / datasource-micrometer 2.3.0 jars:

- **OTEL_* env vars ARE mapped in Boot 4.1.** `OpenTelemetryEnvironmentVariableEnvironmentPostProcessor`
  (since 4.1.0, `spring-boot-opentelemetry`) maps `OTEL_EXPORTER_OTLP_ENDPOINT` to the tracing endpoint
  (+`/v1/traces`), `management.otlp.metrics.export.url` (+`/v1/metrics`) and logs; also OTEL_SDK_DISABLED,
  OTEL_TRACES_SAMPLER(_ARG), OTEL_BSP_*. Opt out: `management.opentelemetry.map-environment-variables=false`.
  So "Boot doesn't read OTEL_EXPORTER_OTLP_ENDPOINT" is wrong; a dev shell with it exported also affects
  tests that use `@AutoConfigureTracing`.
- **Every `SpanExporter` bean is wrapped in Boot's `BatchSpanProcessor`** (`otelSpanProcessor`,
  `@ConditionalOnMissingBean` on BatchSpanProcessor type). A test that registers `InMemorySpanExporter`
  as a bean AND a `SimpleSpanProcessor` over it gets every span twice. Flag count-based assertions.
- **`@Container` static field in an abstract base class restarts per test class** (TC JUnit extension
  stops it when the class store closes); a later class reusing a cached Spring context then points at a
  dead port. Idiomatic fix: `@TestConfiguration` with `@Bean @ServiceConnection` container, or singleton
  static-init start.
- TC 2.x: `org.testcontainers.containers.PostgreSQLContainer` is `@Deprecated`; use
  `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic).
- datasource-micrometer: observation names `jdbc.connection|query|result-set`, span (contextual) names
  `connection|query|result-set`. A "any span with a jdbc.* attribute" assertion is satisfied by the
  connection span alone; ask for name `query` + `jdbc.query[0]`.
- Gradle `test` runs `*IT` classes too (no failsafe split); fine, but naming implies otherwise.
