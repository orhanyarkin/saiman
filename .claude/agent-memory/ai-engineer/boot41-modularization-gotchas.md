---
name: boot41-modularization-gotchas
description: Spring Boot 4.1 split several familiar classes into new artifacts/packages — where to find HealthEndpoint, TestRestTemplate, RestTemplateBuilder, and how to test a web-application-type=none CLI app
metadata:
  type: project
---

Boot 4.1 (as resolved 2026-09-27, Boot 4.1.1) modularized further than Boot 3.x. Classes that used to live in `spring-boot-actuator` / `spring-boot-test` moved to new, separate artifacts. Grep the actual jars in `~/.gradle/caches/modules-2/files-2.1/org.springframework.boot/` when a class "should" be on the classpath but isn't — don't assume Boot 3 package names still apply.

Confirmed moves:
- `HealthEndpoint` and `Status` are no longer in `org.springframework.boot.actuate.health` (that package doesn't exist in `spring-boot-actuator` anymore). They live in the new `spring-boot-health` module, packages `org.springframework.boot.health.actuate.endpoint.HealthEndpoint` and `org.springframework.boot.health.contributor.Status`. `spring-boot-health` is pulled in transitively by `spring-boot-starter-actuator`, so no extra dependency is needed — just fix the import.
- `TestRestTemplate` moved to a new `spring-boot-resttestclient` module, package `org.springframework.boot.resttestclient.TestRestTemplate` (not `org.springframework.boot.test.web.client`). It's pulled in by `spring-boot-starter-webmvc-test` (catalog alias `spring-boot-starter-webmvc-test`), not by `spring-boot-starter-test` alone.
- Registering `TestRestTemplate` as an injectable bean in a plain `@SpringBootTest(webEnvironment = RANDOM_PORT)` needs `@AutoConfigureTestRestTemplate` (`org.springframework.boot.resttestclient.autoconfigure`) — but that autoconfiguration reflectively needs `RestTemplateBuilder`, which lives in yet another module, `org.springframework.boot:spring-boot-restclient`, that is *not* pulled in by `spring-boot-starter-webmvc` or `-webmvc-test`. Result: `NoClassDefFoundError: org.springframework.boot.restclient.RestTemplateBuilder` at context-load time. Avoid the whole chain in simple health-check tests — see next point.
- RANDOM_PORT tests: follow boot4-test-standard.md (RestTestClient).
- A CLI app (`spring.main.web-application-type=none`) still gets a working `HealthEndpoint` bean from `spring-boot-starter-actuator` with no web starter at all — health autoconfiguration doesn't require a web server. Assert `healthEndpoint.health().getStatus()` in a plain `@SpringBootTest`. For "no web server", run SpringApplication for real (see boot4-test-standard.md); `local.server.port` is always null under the default MOCK environment.
