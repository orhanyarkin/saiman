---
name: boot4-modularisation-gotchas
description: Spring Boot 4 split the old fat -web/-test starters into many small modules; expect classes to have moved packages and jars, verify against the resolved Gradle cache rather than guessing
metadata:
  type: project
---

Spring Boot 4 (GA 2026-08-20, this repo pins 4.1.1) split most of the old monolithic starters
(`spring-boot-starter-web`, `spring-boot-starter-test`, etc.) into many focused modules:
`spring-boot-starter-webmvc` (not `-web`), `spring-boot-webmvc`, `spring-boot-webmvc-test`,
`spring-boot-resttestclient`, `spring-boot-restclient`, `spring-boot-http-converter`, and more.
A class you remember from Boot 2/3 may (a) still exist but in a different package, (b) live in a
module that isn't transitively pulled in by the starter you'd expect, or (c) be represented
differently in the module's Gradle Module Metadata than in its legacy POM (the POM literally
contains a comment: "This module was also published with a richer model, Gradle metadata, which
should be used instead" — so `cat` on the `.pom` in the Gradle cache can be misleading about the
real dependency graph Gradle resolves).

**How to verify a class's real location fast** (works without network access once the artifact is
already resolved): `find /home/orhan/.gradle/caches/modules-2/files-2.1/org.springframework.boot
-iname "spring-boot-*.jar" -not -iname "*sources*"`, then `unzip -l <jar> | grep
<SimpleClassName>` across candidates. Faster than guessing from memory or migration-guide prose.

See [[boot4-test-web-client-split]] for the specific `TestRestTemplate` case this surfaced during
M0 T3 (`libs/x402-spring-boot-starter`, `services/seller-api`, `services/ledger`).

**How to apply**: whenever a Boot 4 compile or context-startup error mentions a missing class or
package that used to be in a different place pre-Boot-4, don't add dependencies speculatively —
grep the resolved jars first, then add the exact module via the version catalog aliases already
present in `gradle/libs.versions.toml` (or flag a missing alias to the orchestrator; this agent
must not edit the catalog itself).
