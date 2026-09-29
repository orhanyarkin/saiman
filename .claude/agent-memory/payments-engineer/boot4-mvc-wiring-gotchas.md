---
name: boot4-mvc-wiring-gotchas
description: Spring Boot 4.1/Framework 7 gotchas hit building x402-spring-boot-starter's server package -- WebMvcConfigurer circular deps, ContentCachingResponseWrapper's real buffering behaviour, missing Redis ConnectionDetailsFactory, ObservationRegistry auto-config ordering, micrometer-core absence
metadata:
  type: project
---

Found building `libs/x402-spring-boot-starter` server-side payment enforcement (M1 T2). All
verified against the actual Boot 4.1.1 / Spring Framework 7.0.9 jars, not assumed from Boot 2/3
memory. See [[x402-v2-and-web3j-crypto]] and [[boot4-modularisation-gotchas]] for related notes.

**A `WebMvcConfigurer` bean that needs a `HandlerInterceptor` bean built from
`RequestMappingHandlerMapping` (or anything else `RequestMappingHandlerMapping` itself needs) is a
real circular dependency, not a false positive.** `WebMvcConfigurationSupport` resolves *every*
`WebMvcConfigurer` bean while it is still in the middle of building `RequestMappingHandlerMapping`
itself (configurers can customize path matching/content negotiation on it). If your interceptor (or
anything it depends on) also needs `RequestMappingHandlerMapping` — e.g. to enumerate
`getHandlerMethods()` for a startup-time annotation scan — direct constructor injection deadlocks
context refresh with "Requested bean is currently in creation". Fix: inject
`ObjectProvider<RequestMappingHandlerMapping>` instead and defer `.getObject()` to first actual use
(a `SmartInitializingSingleton.afterSingletonsInstantiated()` hook, or per-call in a filter) — that
runs after every singleton, including the mapping, already exists.

**`ContentCachingResponseWrapper` (Spring Framework 7.0.9, `org.springframework.web.util`) never
writes through to the real response until `copyBodyToResponse()`/`copyBodyToResponse(boolean)` is
called explicitly.** Verified by disassembling the jar (`javap -p -c` on the class and its
`ResponseServletOutputStream`/`ResponsePrintWriter` inner classes): the output stream writes *only*
into an internal `FastByteArrayOutputStream`, never to `getResponse().getOutputStream()`. `setHeader`/
`addHeader`/`setIntHeader`/`addIntHeader` intercept only `Content-Length` (buffered as a field,
applied at flush time) — every other header forwards immediately to the real response via
`HttpServletResponseWrapper`, so headers can be set at any point before the eventual flush and still
land correctly. `resetBuffer()` clears only the body buffer (not headers/status) — the exact tool
for "discard what the handler wrote, then write something else" (e.g. dropping a handler's 200 body
after a downstream settlement call fails, replacing it with a fresh error body). This makes
"buffer the whole response, decide after the fact whether to flush or discard it" patterns work with
zero custom buffering code — don't reach for a hand-rolled `HttpServletResponseWrapper`.

**CORRECTED (reviewer caught this in M1 T2 review round 1): Redis `ConnectionDetailsFactory` DOES
exist in Boot 4.1.1 — it's just not where the general autoconfigure/testcontainers modules are.**
`spring-boot-testcontainers` and `spring-boot-autoconfigure` genuinely have nothing (checked with
`unzip -l` + grep for `Redis.*ConnectionDetailsFactory` — that part was right), but Boot 4's
modularisation means each data module carries its **own** Testcontainers integration in its own
jar: `org.springframework.boot.data.redis.testcontainers.RedisContainerConnectionDetailsFactory`
lives inside `spring-boot-data-redis` itself (verified via `unzip -l` on that specific jar — always
check the *specific* module's jar, not just the general -testcontainers/-autoconfigure ones, before
concluding a `@ServiceConnection` integration doesn't exist for a Boot 4 starter). It matches either
a recognized Redis-family image name or an explicit `@ServiceConnection(name = "redis")` /
`@ServiceConnection(name = "redis")` on a plain `GenericContainer` (confirmed working against a
`valkey/valkey:9.1.2-alpine` image with `@ServiceConnection(name = "redis")` in a real
`@SpringBootTest`). Use it for any `@SpringBootTest`-based selection/wiring test. The plain
`LettuceConnectionFactory` + `RedisStandaloneConfiguration(container.getHost(),
container.getMappedPort(6379))` approach below is still the right one for a **non-Spring-context**
test (bare JUnit, no `@SpringBootTest`) — `@ServiceConnection` only does anything inside a Spring
test context, since it's processed by a `ContextCustomizerFactory`.

**A `StringRedisTemplate`/`RedisConnectionFactory` bean can be created successfully even with no
Redis server reachable at all** (Lettuce connects lazily on first command, not at bean-creation
time). In any test that has `spring-boot-starter-data-redis` on the classpath but isn't actually
running Redis, don't rely on `@ConditionalOnMissingBean(PaymentNonceStore.class)` picking your
in-memory fallback by omission — Boot's own Redis auto-configuration can still produce a
`StringRedisTemplate` bean that then fails at *request* time with a connection error the first time
anything calls it. Register the fallback bean explicitly in test configuration when this matters.

**`ObservationRegistry` auto-configuration ordering matters and there's no compile-time-safe way to
depend on it if it's optional.** `spring-boot-micrometer-observation`'s
`org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration`
(resolved transitively via `spring-boot-micrometer-tracing-test`/actuator, not a hard dependency)
provides the "real" tracing/metrics-wired `ObservationRegistry`. A starter's own
`@ConditionalOnMissingBean ObservationRegistry` fallback bean can win the race against it purely by
configuration-class processing order, silently producing an `ObservationRegistry` with zero handlers
attached (spans/meters just don't happen, no error). Fix: `@AutoConfiguration(afterName =
"org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")` —
`afterName` takes a class name as a *string*, so it orders against a class that may not even be on
the classpath without creating a hard compile dependency on it. Diagnosed by literally seeing zero
spans exported in a test that should have had at least an HTTP server span; don't assume "some
spans" implies "my span" — check for spans at all first.

**`x402-spring-boot-starter`'s own compile/runtime classpath has `io.micrometer:micrometer-observation`
+ `micrometer-commons` but NOT `io.micrometer:micrometer-core`** (checked by resolving
`compileClasspath`/`testCompileClasspath` directly, not guessed). `Counter`/`DistributionSummary`/
`MeterRegistry` (all in `micrometer-core`) cannot be used in this module's own code without adding a
new catalog entry + `build.gradle.kts` dependency — which a T2-scoped agent isn't permitted to do
unilaterally (flag to the orchestrator instead of guessing/adding it). The `Observation` API alone
(`io.micrometer.observation.*`) is enough for tracing/redaction; it just can't directly create
metrics-only Meters (Counter/DistributionSummary) without micrometer-core, though a
`DefaultMeterObservationHandler` in a *consuming* app that does have micrometer-core+MeterRegistry
still converts any Observation into a Timer meter automatically.

**Test acceptance criteria phrased "in-memory OTel or Micrometer test observation registry" are not
equivalent effort.** The OTel span-export path (`@AutoConfigureTracing` +
`InMemorySpanExporter`/`SimpleSpanProcessor`) needs a real `Tracer`/`SdkTracerProvider` bean, which
in this repo comes from `spring-boot-starter-opentelemetry` as an actual app dependency (present in
`services/orchestrator`, not in this starter's own test classpath, which only has the "-test" support
artifacts) — it silently produces zero spans without that dependency, no error either. Given a
choice, `io.micrometer:micrometer-observation-test`'s `TestObservationRegistry` (`.create()`,
implements `ObservationRegistry` directly, `.assertThat().hasHandledContextsThatSatisfy(...)` to
inspect every recorded `Observation.Context`'s key-values) needs nothing beyond
`micrometer-observation` + its test companion and is the more direct tool for asserting on
attribute *content* (e.g. redaction) rather than span *structure*/parentage.
