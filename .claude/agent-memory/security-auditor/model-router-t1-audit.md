---
name: model-router-t1-audit
description: M2 T1 (libs/model-router, commit 1376d73) audit 2026-09-30 - OpenAI key/base-url/OPENAI_LOG env exposure, cap soft-overshoot, cancelled stream = zero cost, carry-forwards for T2/T4/M3
metadata:
  type: project
---

Audit of libs/model-router @ 1376d73 (2026-09-30). Probe: scratchpad Probe.java against the test runtime classpath (gradle init script printing sourceSets.test.runtimeClasspath; needs notCompatibleWithConfigurationCache + --no-configuration-cache), local JDK HttpServer on 127.0.0.1, fake key.

CONFIRMED by probe:
- Spring AI 2.0.1 OpenAiSetup.detectBaseUrlFromEnv reads env OPENAI_BASE_URL / AZURE_OPENAI_BASE_URL when options.baseUrl is null; router never sets baseUrl -> real `Authorization: Bearer <key>` arrived at a non-OpenAI host. Realistic: dev shell exports OPENAI_BASE_URL (LiteLLM etc.) then `make ingest-backfill` runs on the host. Fix: `.baseUrl("https://api.openai.com/v1")` on both options.
- SDK LoggingHttpClient is on by default in ClientOptions (LogLevel.fromEnv): env OPENAI_LOG=debug prints request/response BODIES (prompts, answers) to System.err; Authorization is redacted. Not pinnable via Spring AI builder -> doc/compose-policy + startup WARN if OPENAI_LOG set.
- CostAdvisor.adviseStream accounts only in doOnComplete: subscriber cancel (take(n), client disconnect, timeout) -> cost 0 recorded while OpenAI bills. Errors mid-stream likewise.
- Key does NOT leak via: RouterProperties/OpenAi toString (redacted), OpenAiChatOptions has no toString/Jackson, OkHttp strips Authorization on cross-host redirects, SDK error messages carry provider text only, no Bean Validation, metric tags = tier/outcome only, no prompt text logged.

Design findings: soft cap has no reservation and chat has no maxCompletionTokens (Spring AI default maxRetries=3, timeout 60 s, all uncounted) -> 200-burst overshoot ~2x cap realistic, ~$30-40 worst case (> $10 hard limit); negative counter in Valkey passes the cap check; silent InMemoryCostGuard fallback when no StringRedisTemplate (ingest must add data-redis); per-request prompt options can swap model (mispriced); accounting failure (overflow/negative usage) charges 0.

Carry-forward: seller-api must map non-DailyCap RuntimeException from router (Valkey down) to 503 unsettled and never echo exception messages; DataClass is caller-declared (derive from code path, never from request); M3 tool-loop usage aggregation; Valkey has no auth (anyone on compose net can SET router:cost:*).

**Why:** key is real money; provider limit $10 is the only hard stop.
**How to apply:** on the next router/ingest/seller-api audit re-check baseUrl pin, stream-cancel accounting, reservation, maxCompletionTokens, in-memory-guard fallback.
