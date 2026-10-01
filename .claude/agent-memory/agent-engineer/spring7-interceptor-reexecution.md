---
name: spring7-interceptor-reexecution
description: In Spring Framework 7 RestClient, an outer interceptor calling execution.execute twice re-runs the inner interceptors on the second call (verified with x402 retry + OfferRecorder)
metadata:
  type: reference
---

Spring Framework 7: when an outer `ClientHttpRequestInterceptor` (the x402 `X402PaymentInterceptor`) calls `execution.execute(...)` a second time (the signed retry), the inner interceptors registered after it (orchestrator `OfferRecorder`) run again for that second request, and request attributes survive the starter's `HttpRequestWrapper`. Verified 2026-10-01 by a test that went red→green only because `OfferRecorder` saw the retry's 429.

**How to apply:** an inner interceptor is a reliable place to observe the paid retry's status/headers the starter's exceptions don't carry; don't parse exception messages. Resilience4j `ignoreException(Predicate)` keeps such outcomes out of breaker metrics entirely (vs `recordException` false = counted as success). See [[spring-ai-2-openai-quirks]] for other framework-version notes.
