---
name: spring7-problem-details-echo
description: Spring 7 / Boot 4 Problem Details echo pitfalls — ResponseEntityExceptionHandler null bodies, resource-handler 404s bypassing advice, RestTestClient re-encoding %.
metadata:
  type: reference
---

- `ResponseEntityExceptionHandler` handlers mostly pass `body == null`; the base `handleExceptionInternal` fills it
  from the `ErrorResponse`. An override that sanitises `detail`/`instance` must call super first and mutate the
  *returned* `ResponseEntity`'s `ProblemDetail`, not the incoming body.
- `instance` defaults to the request URI (set by `HttpEntityMethodProcessor` when null) — echoes path variables.
  Set it yourself, e.g. from `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` (encode `{}` as `%7B %7D`).
- Unknown paths: `ExceptionHandlerExceptionResolver` does not apply to `ResourceHttpRequestHandler`, so the 404
  ("No static resource <path>") bypasses `@ControllerAdvice`. API-only services: `spring.web.resources.add-mappings:
  false` → `NoHandlerFoundException` with null handler → the advice handles it.
- A `ResponseEntityExceptionHandler` bean makes Boot's `ProblemDetailsExceptionHandler` back off.
- `RestTestClient.uri(String)` re-encodes `%`; pass an absolute `java.net.URI` to send pre-encoded query text.
- Ledger revenue tests share one context DB: the 100-row revenue cap means tests must use `?payTo=`.

See [[boot4-mvc-wiring-gotchas]].
