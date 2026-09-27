---
name: boot4-test-web-client-split
description: Spring Boot 4 moved TestRestTemplate into a new spring-boot-resttestclient module with a changed package, and it still needs a RestTemplateBuilder that isn't reliably pulled in transitively
metadata:
  type: project
---

Boot 4's module split broke the old `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Autowired TestRestTemplate` pattern from Boot 2/3:

- `TestRestTemplate` moved from `org.springframework.boot.test.web.client` to
  `org.springframework.boot.resttestclient.TestRestTemplate` (module
  `org.springframework.boot:spring-boot-resttestclient`, pulled in transitively by
  `spring-boot-starter-webmvc-test`).
- Even with the import fixed and `@AutoConfigureTestRestTemplate` added (needed because Boot 4 no
  longer auto-registers the bean just from `webEnvironment = RANDOM_PORT`), the bean creation
  failed with `ClassNotFoundException: org.springframework.boot.restclient.RestTemplateBuilder`.
  That class lives in a `spring-boot-restclient` module that is not declared as a transitive
  dependency of `spring-boot-resttestclient` (checked against the resolved POM in the Gradle
  cache on 2026-09-27, Boot 4.1.1) — so `TestRestTemplateTestAutoConfiguration` fails to build the
  `RestTemplateBuilder` it needs at context-refresh time.

**Fix used**: skip `TestRestTemplate` entirely. Inject `@LocalServerPort int port`
(`org.springframework.boot.test.web.server.LocalServerPort`, part of `spring-boot-test`) and hit
the server with `RestClient.create("http://localhost:" + port)` — this also matches this repo's
own convention (CLAUDE.md: "Web: Spring MVC + `RestClient`") over the legacy `RestTemplate`. No
extra test dependency needed beyond `spring-boot-starter-webmvc` + the `spring-boot-starter-test`
that `saiman.java-conventions` already wires in.

**Why**: saved after burning a cycle on `ClassNotFoundException` for `RestTemplateBuilder` in a
`@SpringBootTest(RANDOM_PORT)` health-check test in `services/seller-api` and `services/ledger`
during M0 T3. Re-check if a future Boot 4.1.x patch changes `spring-boot-resttestclient`'s
dependency graph — the module split is very fresh (2026-08-20 GA) and may still be shaking out.

**How to apply**: for any new `@SpringBootTest(webEnvironment = RANDOM_PORT)` test in this repo
that needs to call the running server, default to `@LocalServerPort` + `RestClient`, not
`TestRestTemplate`. See [[boot4-modularisation-gotchas]] for the broader module-split pattern.
