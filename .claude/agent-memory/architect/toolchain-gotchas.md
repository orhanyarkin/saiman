---
name: toolchain-gotchas
description: Version pins and incompatibilities found in the M0 design pass (2026-09-27)
metadata:
  type: project
---
- palantir-java-format must be >= 2.71 on JDK 25 (older versions break at runtime); pinned 2.99.0 explicitly in Spotless.
- Error Prone >= 2.43 needs JDK 21+ to run; the Gradle plugin adds the javac `--add-exports` itself. NullAway runs in JSpecify mode.
- TypeScript pinned to 5.9.x: npm `latest` is 7.x (native port), but typescript-eslint supports <6.1 and openapi-typescript needs ^5.
- jqwik is out: since 1.10 its maintainers ask AI coding agents not to use it (anti-AI clause), it is in maintenance mode and built on JUnit Platform 1.x while Boot 4.1 manages JUnit 6. Replacement decided by an ADR at the start of M4.
- Do not add Spring AI model starters until a milestone needs them; they pull provider auto-config and API-key requirements into CI.

**Why:** these surfaced during the M0 architect pass and would otherwise be rediscovered.
**How to apply:** re-check versions before bumping; see [[boot4-observability]] and [[build-conventions]].
