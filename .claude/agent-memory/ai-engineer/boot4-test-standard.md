---
name: boot4-test-standard
description: Repo-standard patterns for Spring Boot 4.1 web and Testcontainers tests (set by the orchestrator after M0 review)
metadata:
  type: project
---
- HTTP tests: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureRestTestClient` and an injected `org.springframework.test.web.servlet.client.RestTestClient`; assert with `.expectStatus()` and `.expectBody().jsonPath(...)`, never by string-matching JSON. Needs `testImplementation(libs.spring.boot.starter.webmvc.test)` (brings `spring-boot-resttestclient`); no `spring-boot-restclient` needed. Don't use `TestRestTemplate` or hand-built `RestClient`/JDK `HttpClient`.
- Testcontainers: a `@TestConfiguration(proxyBeanMethods = false)` class with `@Bean @ServiceConnection` containers, pulled in with `@Import`, so the container lives as long as the cached context. Not `@Container` static fields (stopped per test class while the context stays cached). Use TC 2.x `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic). See `services/orchestrator/src/test/.../TestcontainersConfiguration.java`.
- Plain `@SpringBootTest` uses a MOCK web environment: it never binds a port, so `local.server.port == null` proves nothing. Prove "no web server" by running `SpringApplication` for real.
- Test classes are named `*Tests` (Gradle runs everything in `test`; there is no failsafe split).
- Boot 4.0 went GA in November 2025; this repo pins 4.1.1.

**Why:** reviewers found the earlier ad-hoc patterns inconsistent or wrong (tautological asserts, per-class container restarts).
**How to apply:** follow these in every service's tests.
