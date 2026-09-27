---
name: boot41-test-client-relocation
description: Spring Boot 4.1's TestRestTemplate moved package and needs an extra starter; use RestClient + @LocalServerPort in orchestrator integration tests instead.
metadata:
  type: project
---

In Spring Boot 4.1.1, `TestRestTemplate` moved to `org.springframework.boot.resttestclient.TestRestTemplate` (module `spring-boot-resttestclient`, pulled in by `spring-boot-starter-webmvc-test`). Autowiring it also now requires the class-level annotation `@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate` (it's no longer automatic for `@SpringBootTest(webEnvironment = RANDOM_PORT/DEFINED_PORT)`).

Even with that annotation, the bean creation fails with `ClassNotFoundException: org.springframework.boot.restclient.RestTemplateBuilder` unless a `RestTemplateBuilder`-providing starter is on the classpath — and no such starter is in this repo's `gradle/libs.versions.toml` catalog (as of M0). Adding one would need an orchestrator-level catalog change.

**Working pattern used in `services/orchestrator`**: skip `TestRestTemplate` entirely. Inject `@org.springframework.boot.test.web.server.LocalServerPort private int port;` and build a plain `org.springframework.web.client.RestClient.create("http://localhost:" + port)` per request. `RestClient` is already on the classpath via `spring-boot-starter-webmvc` (Spring Framework 6.1+), needs no extra starter, and matches CLAUDE.md's existing `RestClient` convention for outbound HTTP.

See `services/orchestrator/src/test/java/io/github/orhanyarkin/saiman/orchestrator/AbstractIntegrationTest.java` for the shared base class (`restClient()` helper + one shared Testcontainers Postgres for the whole hierarchy via `@ServiceConnection` on a static field, which Spring Boot's Testcontainers support explicitly supports being declared in a superclass).

**How to apply**: default to `RestClient` + `@LocalServerPort` for any new `@SpringBootTest(RANDOM_PORT)` integration test in this repo, rather than reaching for `TestRestTemplate`, unless a future ADR adds the `RestTemplateBuilder`-providing starter to the catalog.
