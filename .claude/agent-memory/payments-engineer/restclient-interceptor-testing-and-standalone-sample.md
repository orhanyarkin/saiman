---
name: restclient-interceptor-testing-and-standalone-sample
description: How to test a ClientHttpRequestInterceptor against RestClient with MockRestServiceServer, RestClient's IOException wrapping, and how to build a standalone Gradle sample that reuses the root version catalog -- from the x402 starter's client/ package (M1 T3)
metadata:
  type: project
---

Testing a `ClientHttpRequestInterceptor` end to end (Spring Framework 7.0.9, spring-test 7.0.9):
- `MockRestServiceServer.bindTo(RestClient.Builder).build()` exists and works exactly like the
  long-standing `RestTemplate` overload. Add the interceptor under test to the same builder with
  `.requestInterceptor(...)` (order doesn't matter relative to `bindTo`, since that only swaps the
  builder's request factory); the interceptor sees the mock transport exactly as it would a real
  one, so retries/headers/signatures are exercised for real rather than mocked away.
- `RestClient`'s default (`.retrieve()`) error handling wraps ANY `IOException` thrown out of the
  interceptor chain (including one your own interceptor deliberately re-throws) in
  `org.springframework.web.client.ResourceAccessException` (a `RestClientException`, not an
  `IOException`) -- assert `isInstanceOf(ResourceAccessException.class).hasCauseInstanceOf(IOException.class)`,
  not `isInstanceOf(IOException.class)`. Also wraps non-2xx statuses (4xx/5xx, including 402) in
  `HttpClientErrorException`/`HttpServerErrorException` (both `RestClientResponseException`) unless
  a custom `.onStatus(...)` handler is installed -- so a "does the interceptor return a plain 402
  response normally" test still needs to catch/assert on `RestClientResponseException.getStatusCode()`
  at the `RestClient` call site, even though the interceptor itself returned the 402
  `ClientHttpResponse` without throwing.
- `MockRestServiceServer` response builders: use `withException(IOException)` for a raw I/O failure
  (not a hand-written `ResponseCreator` lambda -- it already exists in
  `MockRestResponseCreators`), and `withStatus(HttpStatus.PAYMENT_REQUIRED)` for x402's 402 (the
  enum constant exists even though it's rarely used elsewhere in the codebase).
- To capture the exact outgoing headers of a *retried* request (e.g. to assert on a signature that
  only exists after a wrapped-request retry, or to grab it for a sample CLI to persist), add a
  second, simple capturing interceptor to the same `RestClient.Builder` **after** the interceptor
  under test: the outermost-added interceptor wraps and calls the inner ones via
  `execution.execute(...)`, so the inner (later-added) one observes the final, rebuilt request on
  each call, including the retry.
- To wrap the original `HttpRequest` for a retry with one extra/overridden header, extend
  `org.springframework.http.client.support.HttpRequestWrapper` and fully override `getHeaders()`
  with a combined, `HttpHeaders.readOnlyHttpHeaders(...)`-wrapped set built in the constructor --
  don't rely on the delegate's `getHeaders()` being mutable.
- `org.springframework.web.client.ClientHttpResponseDecorator` (a delegate-wrapping
  `ClientHttpResponse`, the response-side analogue of `HttpRequestWrapper`) exists in spring-web
  7.0.9 but is **package-private** (`org.springframework.web.client`) -- can't extend it from test
  code in another package. To assert "was `.close()` called on the retried response" (e.g. proving
  an interceptor closes a response it doesn't return), add a second interceptor after the one under
  test that wraps `execution.execute(...)`'s result in a hand-rolled `ClientHttpResponse`
  (`getStatusCode`/`getStatusText`/`getHeaders`/`getBody`/`close` -- only 5 methods, not worth a
  shared helper) whose `close()` sets an `AtomicBoolean` before delegating.
- Real x402 wire fixtures for a settlement/transaction hash MUST be `0x` + exactly 64 hex chars if
  the test exercises `X402PaymentInterceptor`'s commit path -- a short placeholder like
  `"0xabc123deadbeef"` silently used to work before the starter validated `transaction`'s shape
  (T3 review round 2, N1) and then broke every "happy path" test at once when that validation was
  added. Worth a single shared `TX_HASH` test constant per test class rather than inlining short
  fake hashes.

Standalone Gradle sample reusing the root catalog (`libs/x402-spring-boot-starter/samples/console-buyer`,
Gradle 9.8, a build with its own `settings.gradle.kts`, not part of the root's included builds):
- `dependencyResolutionManagement.versionCatalogs.create("libs") { from(files("../../../../gradle/libs.versions.toml")) }`
  in the sample's own `settings.gradle.kts` works and makes `libs.*` accessors available in its
  `build.gradle.kts`, including inside the `plugins { id(...) version libs.versions.x.get() }` DSL
  (confirmed working for `org.springframework.boot` and `com.diffplug.spotless` plugin versions --
  no need to hand-duplicate version numbers for plugins that already have a `[versions]` entry,
  even though the catalog has no `[plugins]` table of its own).
- `mavenLocal { content { includeGroup("io.github.orhanyarkin") } }` alongside `mavenCentral()`
  correctly restricts mavenLocal lookups to just the published starter, so a stale/unrelated
  mavenLocal artifact of some other group can't accidentally satisfy a dependency meant to come
  from Central.
- The published starter deliberately keeps some web3j-crypto types (e.g. raw `ECKeyPair`
  generation) out of its own public API (see [[x402-v2-and-web3j-crypto]]); a sample that needs
  that capability (e.g. a `new-wallet` command) must add `org.web3j:crypto` as its own direct
  dependency, with the same Vert.x/tuweni/connid/jc-kzg-4844 exclusions the starter itself applies,
  to avoid a heavy transitive runtime classpath.
- `Files.createFile(path, PosixFilePermissions.asFileAttribute(perms))` is the atomic
  "create-with-exact-mode-or-fail-if-exists" primitive for writing a secret file (e.g. a generated
  private key) -- one call gets both "0600 from the moment the file exists, no window where it's
  world/group readable" and "refuse to overwrite" (via the built-in `FileAlreadyExistsException`),
  rather than create-temp + chmod + atomic-move.
