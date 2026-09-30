# ADR-0011: The model router is a library (`libs/model-router`)

Status: Accepted (2026-09-29). Amends ADR-0003 ("all LLM calls go through `ModelRouter` in the orchestrator").

## Context
M2 introduces model calls outside the orchestrator: `ingest` embeds chunks and `seller-api` generates cited answers. `seller-api` is the *seller* and must not depend on the buyer's service (the orchestrator), and copy-pasting provider code into each service would break CLAUDE.md rule 6 ("no code calls a provider directly").

## Decision
- `libs/model-router` holds the router: `Tier`, `DataClass`, `ModelRouter` (`chatClient(Tier, DataClass)`, `embeddingModel(DataClass)`), configuration (`saiman.router.*`: routes, prices, daily cap), a data-classification policy check, a **daily USD cap** (Valkey counter, USD tracked as `Money` micro-dollars), token/USD metrics, and fake models for tests.
- Every service that calls a model uses this library and **only** this library; a provider SDK is never called directly. The rule now reads "through the model router (`libs/model-router`)".
- M2 builds the core with the OpenAI adapter (chat + embeddings). M3 adds the remaining adapters, fallbacks, the per-run token budget and the response cache.
- The router starts without an API key and fails closed on the first call.
- Callers cannot override route limits; the router rebuilds every request from the route's options and copies only allowlisted fields.
- Provider keys are secrets (ADR-0009); prices live in config, not code.

## Alternatives
- Router inside the orchestrator and an HTTP call from seller-api: adds a network hop and makes the seller depend on the buyer.
- A copy of the provider wiring per service: violates rule 6 and duplicates the cost guard.

## Consequences
+ One place enforces data classes, the daily cap and cost metrics for ingest, seller-api and the orchestrator.
− A shared library couples release cadence; acceptable in a monorepo.
Revisit if a service outside this repo must use it.

## Amendment (2026-09-30, M3)
- **Cost scopes.** The router gains an optional `ScopedCostGuard`: a caller passes `RouterAdvisorParams.COST_SCOPE` (a run id) and every LLM round trip, including each tool-calling iteration, is reserved against that scope and then the global day; `saiman.router.require-cost-scope` makes scope mandatory for the orchestrator. Each round trip emits a `saiman.model.call` observation with cost and tokens.
- **Fallback stays inside OpenAI in M3** (a second model per tier, circuit breaker, priced at the dearer route). The remaining adapters (Gemini, DeepSeek) and the response cache move from M3 to **M6**, when the evals need them; they are new external services and need the human's go first. The adapter boundary stays abstract (no OpenAI type outside `OpenAiModelFactory`), so they are additions, not rewrites.
- **The daily cap is pinned in code:** a compiled `HARD_CEILING_USD_MICROS = 700_000`; a configured cap above it fails startup. Raising it needs a code change and an ADR amendment.

