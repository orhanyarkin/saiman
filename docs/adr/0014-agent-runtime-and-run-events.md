# ADR-0014: Agent runtime and run events

Status: Accepted (2026-09-30).

## Context
M3 needs a research pipeline that pays for tools, a live step stream, and a trace that shows the run's cost, while keeping tools and providers replaceable (MCP and Gemini/DeepSeek come later).

## Decision
- **Fixed four-step pipeline**, not an open-ended agent: planner (structured plan validated by code against the seller's ticker catalogue), researcher (Spring AI `ChatClient` with the two tools; the loop is Spring AI's `ToolCallingAdvisor`), risk (no tools), synthesis (code keeps only citation ids that appear in the evidence and rebuilds citations from it). Tiers: planner/researcher/risk TIER1, synthesis TIER2 with a config fallback to TIER1 while the model id is unverified.
- **Tools are an interface** (`ResearchTool`, `ToolCatalog`); every paid call goes through `PaidToolGateway`: validate arguments, create the payment intent, call a `PaidResourceClient` (the x402 `RestClient`: no redirects, no retry, a circuit breaker on I/O errors and 5xx only), sanitise the result, emit events. Paid calls are sequential per run. Only allowlisted fields (answer, chunk id, source URL matching the KAP pattern, title) go back to the model, with control/bidi characters stripped, `<`/`>` neutralised, lengths capped, inside a `<tool_data>` block the system prompt calls data; this reduces risk but the boundary is ADR-0013.
- **Run events** (`agent.run-step.v1`, docs/events): `RunEventAppender` takes `seq` from `UPDATE run SET next_seq = next_seq + 1 RETURNING` (safe against concurrent writers), an AFTER_COMMIT listener pushes to an in-memory bus, and the SSE endpoint registers, replays from the DB and de-duplicates by seq. Kafka starts in M4 through the outbox.
- **Execution:** virtual threads behind a semaphore (`max-concurrent` 2, matching the seller's per-payer in-flight limit); a root observation `saiman.run` per run (own trace id, returned by the API) with a child span per step; per paid call a `saiman.run.payment` span; at the end the root span carries `saiman.run.cost.payments_usdc_atomic`, `saiman.run.cost.llm_usd_micros` and `saiman.run.cost.total_usd_micros`, equal to the persisted totals.
- Nothing outside the OpenAI adapter names a provider type; nothing outside `ResearchTool` implementations names a transport.

## Alternatives
- A free-form agent loop: harder to bound and to test; the fixed pipeline keeps cost and payments predictable.
- Kafka for the stream now: adds a broker dependency to the run path before the ledger needs it.

## Consequences
+ A small, testable core; MCP and other providers are additions.
− Single-instance executor and in-memory bus; horizontal scale needs the M4 relay.
− The planner's ticker catalogue is another seller call (free).
