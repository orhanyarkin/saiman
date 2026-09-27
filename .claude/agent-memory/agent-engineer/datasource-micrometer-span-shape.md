---
name: datasource-micrometer-span-shape
description: datasource-micrometer-spring-boot span names are short ("query"/"connection"/"result-set"), not "jdbc.*" — the jdbc.* prefix is only in attribute keys.
metadata:
  type: project
---

`net.ttddyy.observation:datasource-micrometer-spring-boot:2.3.0` (used for JDBC spans per ADR-0006) produces `SpanKind.CLIENT` spans named exactly `connection`, `query` and `result-set` — not `jdbc.connection` / `jdbc.query` / `jdbc.result-set` as the span *name*. The `jdbc.` prefix only shows up in the span's **attribute keys**, e.g. `jdbc.query[0]=select now()`, `jdbc.datasource.name`, `jdbc.datasource.pool`, `jdbc.datasource.driver`, `jdbc.row-count`.

So when asserting "a JDBC span exists" from an `InMemorySpanExporter` in a test, match on attribute key prefix, not span name:

```java
spansForTrace.stream().anyMatch(span ->
    span.getAttributes().asMap().keySet().stream().anyMatch(k -> k.getKey().startsWith("jdbc.")));
```

A `span.getName().startsWith("jdbc")` assertion will fail. Confirmed empirically in `services/orchestrator/src/test/java/.../system/PingTracingIT.java` against real exported `SpanData` (Boot 4.1.1, OTel SDK 1.62.0).

**How to apply**: use this pattern anywhere in the repo that asserts on JDBC spans from `InMemorySpanExporter` (ingest, ledger, seller-api integration tests will hit the same library).
