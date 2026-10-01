# M3 design contract — orchestrator, spend control, router scopes

Binding for every M3 task. Source: architect pass (2026-09-30) plus the human's decisions: MCP tools move to Stretch, provider fallback stays inside OpenAI until M6, settle-before-serve is an ADR now and lands in M4. ADRs: 0013 (spend control), 0014 (agent runtime, run events), 0015 (settle-before-serve), amendments to 0011 and 0009.

**Acceptance (docs/PLAN.md):** a research run completes end to end with >= 2 paid calls; exceeding the budget blocks payment *before* signing (test proves it); a prompt-injection document cannot raise a budget (test proves it); per-run USD cost is visible in traces.

## Standing rules for all M3 tasks
1. **Abstractions stay abstract.** The two research tools (`disclosureSummary`, `askDisclosures`) are `ResearchTool` implementations behind an interface and a `ToolCatalog`; the agent code never names a transport. The paying HTTP client sits behind `PaidResourceClient`. A future MCP transport (Stretch) is one new `ResearchTool`/client class. Likewise the LLM side stays behind `ModelRouter`/`ModelFactory`: no class outside the OpenAI adapter (`OpenAiModelFactory` and its options code) may import an OpenAI type, so a Gemini/DeepSeek adapter (M6) is one new factory class plus config. M3 code must not change when they arrive.
2. **Plaintext allowlist is narrow.** `http://seller-api:8081` is the only plaintext exception. `x402.client.allowed-plaintext-hosts` holds exact host names (no wildcard, no suffix, no IP ranges, no port-less patterns that match other hosts); loopback behaves as today; the starter refuses to start if the list is non-empty while the configured network is not the Base Sepolia testnet (it stays closed for any mainnet configuration), and the list is empty by default.
3. **The daily LLM cap is pinned in code.** `saiman.router.daily-cap-usd-micros` keeps its default of 700000 ($0.70) but the router gets a compiled `HARD_CEILING_USD_MICROS = 700_000`: a configured value above it fails startup. Raising the ceiling is a code change plus an ADR-0011 amendment, never an environment variable. (The human also keeps a provider-side monthly limit on the OpenAI project; M3 starts live LLM runs.)
4. **Known limit, stated openly** (THREAT_MODEL, README, ADR-0015): because the seller serves before it settles, a burst of unsettled runs can exhaust the day's unsettled budget and every payer then gets 429 until UTC midnight. Spend is bounded; availability is not. The fix is ADR-0015 (M4).
5. Money is integer atomic units; testnet only; secrets only as mounted files; no event, log or span attribute carries an idempotency key, nonce or signature (the tx hash is allowed); model text never becomes a limit, a URL, a payee or an amount.
6. Every task report ends with "Spring notes".

## T0 — contracts (this document, orchestrator)
- `libs/shared` package `io.github.orhanyarkin.saiman.shared.run`: `RunEvent`, `RunEventType`, `RunEventData` (sealed payloads), `AgentStep`, `DenyReason`, `RunCost`. Schema doc: `docs/events/agent.run-step.v1.md`. No Kafka in M3: the `run_event` table is the append log; M4 relays it through the outbox.
- ADR-0013/0014/0015, ADR-0011 and ADR-0009 amendments, PLAN/ARCHITECTURE wording, THREAT_MODEL and README limit notes.

## T1 — starter + seller-api (payments-engineer, model: opus; dirs: libs/x402-spring-boot-starter, services/seller-api)
- `x402.client.allowed-plaintext-hosts` per standing rule 2 (F-A: `X402PaymentInterceptor.requireSecureOrLoopback` currently refuses `http://seller-api:8081`); tests: exact host passes, sibling host / wildcard / suffix trick refused, startup fails on a non-testnet network with a non-empty list.
- `SpendGuard.signed(SpendReservation, Eip3009Authorization)` default no-op, called after signing and BEFORE the paid retry is sent; if it throws, nothing was sent: the interceptor releases the reservation and rethrows (fail closed). Test proves it.
- `X402PaymentContext.validBefore(HttpServletRequest)`; the seller handler deadline becomes `min(configured, validBefore - now - settle margin)`, skip the model call if less than the model timeout remains; startup assertion that `deadline + facilitator read timeout + 5 s <= minWindowSeconds`; test with a short authorization.
- Free `GET /v1/tickers` on seller-api (proxied from ingest, cached) so the planner knows the catalogue.
- Republish the starter to mavenLocal if the orchestrator build needs it. Per-task security audit: yes (opus).

## T2 — model router (agent-engineer; dir: libs/model-router)
- `ScopedCostGuard` (optional bean): `reserve(scopeId, Money)` throws `ScopeBudgetExceededException`; `settle(scopeId, reservation, actual)`. Param key `RouterAdvisorParams.COST_SCOPE = "saiman.router.cost-scope"`, set by the caller via `.advisors(a -> a.param(COST_SCOPE, runId))`. `CostAdvisor` reserves against the scope first, then the global day, and releases the scope if the global reservation fails. `saiman.router.require-cost-scope=true` (orchestrator only) refuses unscoped calls. Valkey implementation keyed `run:{id}:llm`; test that the scope survives Spring AI's recursive `ToolCallingAdvisor` iterations (a fake model emitting one tool call is charged twice under the same scope).
- Observation `saiman.model.call` per round trip: low-cardinality `tier, model, outcome`; high-cardinality `saiman.cost.usd_micros, tokens.in, tokens.out, saiman.cost.scope`.
- Fallback inside OpenAI only: `routes.<tier>.fallback {model, max-completion-tokens, reasoning-effort}`, a Resilience4j breaker per route, fallback only on connect/timeout/429/5xx, the fallback route's data class is checked, the reservation is priced at the max of the two routes. `HARD_CEILING_USD_MICROS` per standing rule 3. Per-task audit: no (milestone audit covers it).

## T3 — spend control (agent-engineer; dir: services/orchestrator packages budget, payment, approval + Flyway V2)
`BudgetSpendGuard implements SpendGuard` is the ONLY `SpendGuard` bean (test: F-C, the starter's `PropertiesSpendGuard` fallback must not be active). Design in ADR-0013; schema:
```sql
run(id uuid pk, question text, status text check in (QUEUED,RUNNING,AWAITING_APPROVAL,SUCCEEDED,FAILED),
    budget_atomic bigint > 0, reserved_atomic bigint >= 0, committed_atomic bigint >= 0,
    llm_budget_usd_micros bigint, llm_cost_usd_micros bigint >= 0, next_seq int, trace_id text,
    result jsonb, failure_code text, created_at, started_at, finished_at,
    CHECK (reserved_atomic + committed_atomic <= budget_atomic));
-- BEFORE UPDATE trigger raises if NEW.budget_atomic <> OLD.budget_atomic (the budget is immutable)
run_event(run_id, seq, type, payload jsonb, created_at, PK(run_id, seq));
payment_intent(id uuid pk, run_id fk, idempotency_key text unique, tool, args_hash, resource,
    status check in (PENDING,AWAITING_APPROVAL,APPROVED,RESERVED,SIGNED,SETTLED,HELD,RELEASED,DENIED,REJECTED,EXPIRED),
    amount_atomic, pay_to, network, asset, payer, auth_nonce, valid_before bigint, tx_hash, deny_reason, created_at, updated_at);
spend_day(day date pk, reserved_atomic >= 0, committed_atomic >= 0);
approval(id uuid pk, payment_intent_id unique fk, run_id, amount_atomic, pay_to, resource,
    status check in (PENDING,APPROVED,REJECTED,EXPIRED), requested_at, decided_at, expires_at);
```
- Tests (acceptance 2): run budget 20000, price 10000, three calls give 2 SETTLED + 1 `PAYMENT_DENIED(RUN_BUDGET)`, the spy signer is called exactly twice and the stub sees exactly two `PAYMENT-SIGNATURE` requests; same against the daily cap; 16 threads reserving 10000 each against 50000 -> exactly 5 granted; held reservation (stub answers 500 after the signature) keeps counting and the next call is denied; approval above the threshold; redirect not followed (real socket); only-one-`SpendGuard` bean.
- Per-task audit: yes (opus recommended).

## T4 — agents, tools, SSE (agent-engineer; dir: services/orchestrator packages run, agent, tool, events)
Pipeline and runtime in ADR-0014. Tests: acceptance 1 (hermetic e2e: scripted model, >= 2 settled calls through a real x402 stub), acceptance 3 (`PromptInjectionCannotRaiseBudgetTests`: adversarial scripted model = fully compromised model; injection text inside a tool result and inside the question; stub 402 offering a non-allowlisted payTo; assert budget unchanged, reserved + committed <= budget, signer calls <= budget / price, zero signatures for the bad payee, no approval ever APPROVED, only the configured base URL contacted, tool definitions are exactly the two tools with ticker/question parameters), acceptance 4 (in-memory exporter: root span cost attributes equal the DB totals).

**T4a interfaces as built (T4b plugs in here):** `run.ResearchPipeline#execute(RunContext) -> RunOutcome` (`Succeeded(Report)` | `Failed(FailureCode)`); `RunContext(runId, question, budget, events, tools, costScopeId, modelCalls)`, where `tools` is a per-run `tool.RunToolSession` (`definitions()`, `call(toolName, argumentsJson)` never throws and returns a `<tool_data>` block or a fixed `ToolMessages` string, `knownTickers()`, `evidence()`) and `modelCalls.record(ModelCallCompleted)` adds the LLM cost to the run row and emits the event in one transaction. `ResearchTool#prepare(ToolInvocation) -> ToolCall` (`send()` may be repeated after an approval with the same intent), so the gateway never names a transport. `GET /api/v1/runs/{runId}/events` is content-negotiated: `text/event-stream` is the live stream and `application/json` the complete ordered export (same envelopes). New config `saiman.orchestrator.events {heartbeat: 15s, timeout: 30m, max-streams-per-run: 4, max-streams: 64}`. Settled results are kept, sanitised, in `tool_result` (V4) for the per-run dedupe.

## T5 — compose and scripts (infra; dirs: deploy, scripts except capture-demo, Makefile, .env.example, .github)
Orchestrator secrets `x402.client.private-key` (from `secrets/x402_buyer_private_key`, long syntax `target:`, 0644 inside the 0700 dir per the ADR-0009 amendment; the human creates it) and `openai_api_key`; `X402_CLIENT_ALLOWED_PAY_TO` from the payTo; `x402.client.allowed-plaintext-hosts=seller-api`; depends on seller-api healthy; the compose policy allows exactly these; `make research-run`, `make research-approve`; `secrets-check` knows the new file.

## T6 — live runs (ai-engineer + orchestrator running make; dirs: orchestrator prompts, router config)
2-3 live runs on Base Sepolia, >= 2 settled tx hashes each, cost visible in Jaeger; verify or replace the `gpt-5`/`gpt-5-nano` ids.

## T7 — audit and docs (orchestrator + security-auditor, opus)
Milestone audit; "Orchestrator / spend control" section in THREAT_MODEL; gaps updated; PROGRESS.

## Orchestrator HTTP (springdoc, Problem Details)
```
POST /api/v1/runs {question: 3..500 chars, budgetAtomic?: long <= max-run-budget} -> 202 {runId, eventsUrl, traceId}
GET  /api/v1/runs/{runId}                    run summary incl. cost and status
GET  /api/v1/runs/{runId}/events             SSE; Last-Event-ID = seq; replay from DB then live; 15 s heartbeat; ends after a terminal event
POST /api/v1/runs/{runId}/approvals/{approvalId} {decision: APPROVE|REJECT}   needs application/json + X-Saiman-Csrf header
```
There is deliberately no endpoint that changes a budget.

## Config
```yaml
saiman.orchestrator:
  seller.base-url: http://seller-api:8081     # config only; no tool takes a URL
  spend: {daily-cap-atomic: 1000000, default-run-budget-atomic: 50000, max-run-budget-atomic: 200000,
          approval-threshold-atomic: 10000, approval-timeout: 5m, max-paid-calls-per-run: 4, max-tool-calls-per-run: 6}
  runs: {max-concurrent: 2, llm-budget-usd-micros: 150000}
x402.client: {private-key: (configtree), max-amount-per-request: 20000, allowed-pay-to: ${X402_SELLER_PAYTO_ADDRESS},
              allowed-plaintext-hosts: [seller-api]}
saiman.router: {require-cost-scope: true}
```
Seller-api allows at most 2 in-flight runs per payer and the orchestrator is one wallet: paid calls are sequential within a run and at most 2 runs run concurrently (F-D).

Approval threshold: the shipped value is 10000 (strictly above), so the 0.01 USDC summary tool never waits and the 0.02 USDC questions tool waits for a human; `ResearchRunApprovalTests` uses a 16000 price against a 15000 threshold. OpenAPI (springdoc) for the orchestrator and seller-api is deferred to M5, when the typed web client needs it.

## Runtime limits added after the T4 audit
A run has a wall-clock deadline (`saiman.orchestrator.runs.deadline`, default 15 min; checked at step start, tool-call start and approval waits, failing with `RUN_DEADLINE`); `/api` request bodies above 16 KB (or of unknown length) get 413; SSE allows 4 streams per run (a new one evicts the run's oldest) and 64 overall; a finished run is replayed from the database (204 when nothing is left after `Last-Event-ID`); every free-text field leaving the process goes through `UntrustedText.forDisplay`.

## Deferred
MCP tools -> Stretch. Non-OpenAI adapters and the LLM response cache -> M6. Settle-before-serve implementation, on-chain reconciliation of held reservations, Kafka `agent.run-step.v1` via the outbox -> M4. Per-IP `/verify` limits, separate verify/settle breakers, Valkey ACLs, ingest shared secret, orchestrator auth -> M6 hardening. `GET /internal/v1/corpus-version`, cache/422 metrics -> M6. Web run view -> M5.
