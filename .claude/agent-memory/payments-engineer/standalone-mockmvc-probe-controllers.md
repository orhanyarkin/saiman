---
name: standalone-mockmvc-probe-controllers
description: Forcing error paths through the real ApiExceptionHandler without polluting the shared test context or OpenAPI contract; chain_tx_hash semantics in ledger reconciliation.
metadata:
  type: feedback
---

To force a 500/503 through MVC in ledger tests, use `MockMvcBuilders.standaloneSetup(probe).setControllerAdvice(new ApiExceptionHandler())`
with a **non-static inner `@Controller`** probe class (+ `@ResponseBody`).

**Why:** standalone MockMvc ignores handlers without `@Controller` (404), but a static nested or top-level `@Controller`
in test sources can get component-scanned into the shared `@LedgerIntegrationTest` context and the OpenAPI contract.
Inner (non-static) classes are not "independent", so scanning never picks them up.

**How to apply:** any test that needs a fake route with the real advice. Mockito `thenThrow(SomeException.class)`
instantiates exceptions with package-private constructors (objenesis) — handy for ReconciliationService exceptions.

Ledger fact: `payment.chain_tx_hash` is written only when a canonical receipt (succeeded, <= safe block, emits
AuthorizationUsed for payer+nonce) is found; it does NOT imply payer->payTo/amount matched — PARTY/AMOUNT_MISMATCH rows
carry that. Revenue `sale_verified` needs both (M5 re-review, 2026-10-05). See [[m4b-audit-lows-findings]].
