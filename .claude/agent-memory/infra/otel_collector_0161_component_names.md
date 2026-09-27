---
name: otel-collector-0161-component-names
description: otel/opentelemetry-collector:0.161.0 core distro deprecates the "otlp" exporter alias; use otlp_grpc for gRPC OTLP exporters
metadata:
  type: project
---

The core distribution image `otel/opentelemetry-collector:0.161.0` logs
`"otlp" alias is deprecated; use "otlp_grpc" instead` at startup if an exporter is
declared as `otlp/<name>` with a gRPC `endpoint`. The receiver alias `otlp` (combined
grpc+http under `protocols:`) is NOT deprecated — only the plain gRPC *exporter* type
needs the `otlp_grpc` name now. Fixed in `deploy/compose/otel-collector.yaml` by naming
the Jaeger exporter `otlp_grpc/jaeger` instead of `otlp/jaeger`.

Confirmed manifest for 0.161.0 core (`otelcol`, not `otelcol-contrib`): receivers
otlp, hostmetrics, jaeger, kafka, prometheus, zipkin, nop; processors batch,
memory_limiter, attributes, resource, span, probabilistic_sampler, filter; exporters
debug, nop, otlp, otlphttp, file, kafka, prometheus, prometheusremotewrite, zipkin;
extensions zpages, health_check, pprof. Source:
https://raw.githubusercontent.com/open-telemetry/opentelemetry-collector-releases/v0.161.0/distributions/otelcol/manifest.yaml

**Why:** the M0 design doc (architect pass) flagged that "some core components were
renamed recently and aliases may be deprecated" and asked T1 to verify against the
0.161 docs before shipping — this is the actual instance found.

**How to apply:** whenever bumping the collector version, check the startup logs
(`docker logs <collector-container>`) for `deprecated` warnings, not just for crashes —
deprecated aliases still work (with a warning) until removed in a later release, so
`docker compose config` / `terraform validate`-style static checks won't catch them.

See also [[docker-compose-local-stack-gotchas]] for the rest of the deploy/compose setup.
