---
name: spring-test-pitfalls
description: Recurring Saiman test-design pitfalls - tautological MOCK-env "no web server" asserts, CommandLineRunner beans firing inside @SpringBootTest
metadata:
  type: feedback
---

Seen first in the M0 T4 review (2026-09-27). See also [[boot4-review-checklist]] (test HTTP client drift, OTLP metrics default).

- **Tautological "no web server" tests.** `@SpringBootTest` defaults to `WebEnvironment.MOCK`, which never binds a port. Asserting that `local.server.port` is null therefore passes even for a servlet app. A real check boots the app with `SpringApplication.run(...)` and inspects the resulting context and environment.
- **CommandLineRunner/ApplicationRunner beans run inside `@SpringBootTest`.** This was confirmed in the evals test log. A runner that only logs is harmless, but once it makes LLM or payment calls (evals in M6), every context-loading test makes those calls too. Require a property or profile gate before real work goes into a runner.

**Why:** agent self-verification missed both because the tests were green. One is a test that cannot fail; the other is a side effect that only causes harm later.
**How to apply:** on every service review, grep new tests for `local.server.port` and new code for `CommandLineRunner`/`ApplicationRunner` beans.

Added 2026-09-28 (M0 T6 review), verified against Boot 4.1.1 jars/sources:

- **Actuator 404 tests cannot tell access from exposure.** With `web.exposure.include: health,info`,
  `/actuator/env` is 404 whatever `management.endpoints.access.default` says, so a test that only
  asserts 404 would still pass after someone deletes the access setting. To prove access, add a
  test that sets `management.endpoints.web.exposure.include=*` and still expects 404.
- **`$.env` never exists in /actuator/info.** `EnvironmentInfoContributor` binds `info.*` and merges
  it at the ROOT (`builder.withDetails(map)`), so `jsonPath("$.env").doesNotExist()` can never fail.
  To check "build only", assert the root keys (`containsOnlyKeys("build")`).
- Health `show-details` defaults to `never`, and `show-components` falls back to it, so a
  `$.components doesNotExist` test only catches a later loosening, not a missing setting.
- **Test-wide property defaults** (for example a runner gate) belong in
  `src/test/resources/config/application.yaml`, which Boot layers over the main
  `classpath:application.yaml` instead of shadowing it. Per-test `properties=` means every new test
  has to remember to set it.
