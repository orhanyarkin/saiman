---
name: m2-close-audit
description: M2 milestone-end audit 2026-09-30 (branch m2-rag @ 0e36d96) - router OpenAiChatOptions passthrough drops route limits, MKK base URL unpinned, guard after retrieval, deadline not anchored to validBefore, ingest-backfill configtree loads buyer.key
metadata:
  type: project
---

Read-only audit of m2-rag @ 0e36d96 (2026-09-30), no probes (read Spring AI 2.0.1 sources from gradle cache).

Facts verified in Spring AI 2.0.1 OpenAiChatModel: buildRequestPrompt uses prompt options AS-IS when non-null (no merge with model defaults); createRequest casts to OpenAiChatOptions; client (baseUrl/apiKey) built once at construction, so per-request baseUrl is ignored. => router's LazyChatModel.openAiPrompt (OpenAiModelFactory:153) replaces GENERIC options with route options (good) but passes caller OpenAiChatOptions through untouched: no max_completion_tokens, n, extraBody, customHeaders, reasoningEffort dropped; RouteCosting estimate assumes route max + n=1. Latent (no caller today) -> M3 blocker. Generic ToolCallingChatOptions also get replaced -> tool callbacks silently dropped.

Findings: Medium router passthrough; Medium MKK base-url config/env-driven, no https/host pin (Basic credential egress; compose forbidden_env lacks SAIMAN_INGEST_MKK_*/SPRING_CONFIG_*); Low guard.tryStart after ingest retrieval (429/422-pre-model release claim -> one signature replays embeddings + /verify); Low Deadline starts at handler (verify retries + 20 s model timeout not bounded by validBefore; MIN window 45 constant vs configurable deadline/readTimeout, no startup check); Low make ingest-backfill --saiman.secrets-dir=secrets/ configtree loads buyer.key into ingest env; Low ingest /internal loopback reachable from dev browser (retry-dlq CSRF, DNS rebinding); Low citations title/excerpt unscrubbed + scrubLinks misses //host, javascript:, bare domains; Info minWindow bound allows > offered window; SafePrint passes C1/bidi.

Verified OK: markWorkDone placement (provablyNotSent excludes cap/data-class/not-sent), dispatch_error reset+restore, filter order (-100) inside RequestContextFilter so onSettled sees STARTED attr, payer = authorization.from after ecrecover, SQL parameterised, MKK credential only default header + DONT_FOLLOW + status/ER-code-only exceptions, compose secrets allowlist, BuyCommand json-file realpath checks, no mainnet refs.

**How to apply:** at M3 start re-check router option handling with a wire-level test before the orchestrator uses tools; verify MKK host pin and guard-before-retrieval landed.
