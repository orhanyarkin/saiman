---
name: spring-security7-resource-server-notes
description: Spring Security 7.1 / Boot 4.1 opaque-token resource server facts and the env-var map binding trap found while building libs/api-security (M6-T1, 2026-10-05)
metadata:
  type: reference
---

- **Env vars cannot bind a Map under a dashed segment.** `SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256` did NOT bind to `saiman.auth.service-tokens.ledger.sha256` (map stayed empty), while dashed *leaf* lists (`SAIMAN_AUTH_READER_TOKEN_SHA256` -> `reader-token-sha256`) did bind via Boot's legacy dash->underscore mapping. Fix used: nested record `service.tokens.<caller>.sha256`. Prove env names with an `ApplicationContextRunner` that replaces the `systemEnvironment` source with a `SystemEnvironmentPropertySource`.
- Boot 4.1 `UserDetailsServiceAutoConfiguration` (default generated password) is suppressed by the *class* `OpaqueTokenIntrospector` being on the classpath (`MissingAlternative...` condition), not by a bean.
- `OpaqueTokenAuthenticationProvider` rethrows `BadOpaqueTokenException` as `InvalidBearerTokenException(e.getMessage())`: keep the introspector's message fixed. Missing `iat`/`exp` attributes are fine.
- Two 401 paths: missing token -> ExceptionTranslationFilter (`exceptionHandling().authenticationEntryPoint`), bad token -> bearer filter failure handler (`oauth2ResourceServer().authenticationEntryPoint`). Set both, and `accessDeniedHandler` on both (default bearer one adds `WWW-Authenticate: ... insufficient_scope` to 403).
- `FilterChainProxy` DEBUG ("Securing GET /x?access_token=...") and authorization TRACE lines log the query string: a token in a URL leaks at DEBUG no matter what the resolver does.
- `OAuth2ResourceServerConfigurer.bearerTokenResolver(...)` still exists in 7.1 alongside `authenticationConverter(...)`. `RoleHierarchyImpl.fromHierarchy` and `withDefaultRolePrefix()` builder both exist.
- `MockMvcTester`/`MvcTestResult`: resolved exception via `result.getMvcResult().getResolvedException()`.
