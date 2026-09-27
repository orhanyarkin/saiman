---
name: build-conventions
description: Build and image decisions from ADR-0007
metadata:
  type: project
---
Conventions live in the included build `build-logic/` (precompiled script plugins `saiman.java-conventions`, `saiman.java-library`, `saiman.spring-boot-service`), not buildSrc or `subprojects {}`. Boot BOM via Gradle `platform()`. Images via `bootBuildImage` (Paketo), one arch per invocation; arm64 built natively on `ubuntu-24.04-arm` runners, no QEMU.

**Why:** ADR-0007, accepted 2026-09-27.
**How to apply:** new modules apply one convention plugin id; don't add Dockerfiles. See [[toolchain-gotchas]].
