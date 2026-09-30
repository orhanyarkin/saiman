---
name: m2-t4-seller-rag-audit
description: M2 T4 (seller-api RAG summary + paid questions endpoint, 8a23f4b) audit 2026-09-30 - LLM runs before settle, non-2xx releases nonce, expiry-window free compute, injection/cache/SSRF verdicts
metadata:
  type: project
---

Audit of commit 8a23f4b (2026-09-30), by code reading (no probe; RagTestBase needs Docker/Valkey).

Ordering facts (verified in RequiresPaymentInterceptor + X402SettlementFilter):
- preHandle (decode, offer-equality, ecrecover, CLAIM nonce, facilitator /verify) runs BEFORE argument resolution, so @Pattern/@Valid/body-parse 400s happen AFTER claim+verify; filter then releases the claim on any non-2xx. A malformed request never burns a valid authorization (M1 follow-up answered) but costs one facilitator /verify.
- Body is read only after payment verified; unpaid requests never reach body parsing.
- Claim HELD on settle failure and on dispatch_error (unhandled exception); RELEASED on any handler non-2xx.

Findings: F1 High - handler-induced non-2xx (422/502/503 after the LLM ran) releases the claim: attacker-triggerable via the question, unlimited free LLM runs per funded key, bounded only by the router daily cap ($0.70 default, ~100-230 runs) which then DoSes all buyers and ingest embeddings (shared cap). F2 High - min validBefore window = facilitator readTimeout(15s)+5 = 20s, no handler-runtime margin; attacker picks a 20s window and a long-answer question, LLM finishes after expiry, settle fails, free answer computed; openai timeout 30s x (1+1 retry) can exceed the whole 65s window. F3 Medium - drain-between-verify-and-settle concurrency (N auths from one wallet holding one price). F4 Medium - summary has no single-flight / negative cache. Others: unbounded JSON body before @Size, summary 404 path costs an embedding, sourceUrl/title not host-pinned, answer text un-validated (URLs), Valkey summary-cache poisoning (no auth), console buyer --json-file reads any absolute file (/proc/self/environ size 0 passes isRegularFile) and POSTs it to --url.

Verified OK: router T1 items closed (baseUrl pinned, reservation, maxCompletionTokens, OPENAI_LOG warn); data class fixed in code (INTERNAL question, PUBLIC summary); constant system prompt, question/chunks only in user msg, < > neutralised, chunk ids regex-checked, citations rebuilt from retrieved set; fixed-string Problem Details, no question/chunk/exception text in logs (class names only); IngestClient base URL config-only, fixed paths, ticker in body not URI, Redirect.NEVER; cache key = ticker(validated)+sha256(corpusVersion), summary input fully server-controlled; seller-api holds openai key via compose secrets only.

**How to apply:** when M3 adds settle-before-serve / per-payer limit / handler deadline, re-check F1/F2 with tests (replay after 422 must not re-run model; short-window auth rejected for LLM endpoints).
