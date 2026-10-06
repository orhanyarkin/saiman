---
name: m6-t3-seller-audit
description: M6 T3 seller-api half audit (2026-10-05) - no Crit/High; eval drains shared router day cap, owner cred residual, /internal no body limit, firewall wraps /v1
metadata:
  type: project
---
Per-task audit of eacb35b/a6f9117/acf7840/7006362 (seller /internal chain, V3 grants, eval API, ModelCostMeter, caller run guard). No Crit/High.

- Medium: eval caller (60/h, no day cap, no router cost scope) spends the SHARED router day cap ($0.70 default) -> paid upfront buyers settle then 503 + credit note until UTC midnight. Fix: RouterAdvisorParams scope "evals" with own USD budget (orchestrator already uses scopes) or seller.eval.max-runs-per-day.
- Medium carry-forward: pg_seller_api_owner_password mounted in running seller-api; V3 "append-only" holds only vs app role. THREAT_MODEL residual (5) line ~309 still says Host allowlist is not auth (now outdated).
- Low: BodySizeLimitFilter only on /v1/* -> eval POST unbounded (authenticated only); settlement UPDATE table-wide (column GRANT tx_hash,outcome,reason_code works with ON CONFLICT); published_at null/out-of-range -> DateTimeException/NPE before router try (paid: 500+credit note; eval: unmapped 500).
- Info: FilterChainProxy wraps EVERY request (incl. /v1) in StrictHttpFirewall before matching (verified in spring-security-web 7.1.1 source): ; // %2F -> 400 pre-x402; header/param values validated lazily on read (Spring 7 ServletServerHttpRequest headers are lazy adapter). Route rules method-agnostic (ledger/orch pin methods).
- Sound: hasAuthority(SERVICE_<caller>) per route, human tokens 403 (tested); empty digest -> warn + deny; auth-off -> Boot default chain locks /v1 too (security -100 runs before x402 LOWEST-100: no claim); markWorkDone no-op without attempt; router passes app ObservationRegistry (ObjectProvider); CostAdvisor stops synchronously, keepEstimate tags cost on failures; caller keys seller:runs:caller:* disjoint from hex payer keys, counted='0'; nginx check fails on any /internal.
**How to apply:** at M6 close verify eval cost scope/day cap, body limit on /internal, owner residual + residual (5) text updated.
