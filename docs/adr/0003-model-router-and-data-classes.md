# ADR-0003: Own model router with tiers, fallbacks and data-classification policy

Status: Accepted (revised 2026-09-27: `IModelRouter` renamed to `ModelRouter` to follow Java naming; no change in substance). Amended 2026-09-29 by ADR-0011: the router is a library (`libs/model-router`) used by every service that calls a model, not a component of the orchestrator.

## Context
Prices differ by 100× across models; cheap open-weight models (DeepSeek, Qwen, GLM) are attractive but their first-party APIs process data in China, which matters for fintech data-residency expectations (KVKK). We also want provider independence and per-call cost telemetry.

## Decision
All LLM calls go through `ModelRouter` (a library, `libs/model-router`, per ADR-0011):
- **Tiers**: tier0 (routing/extraction), tier1 (tool-use agent), tier1-premium (hard runs, eval comparison), tier2 (synthesis/judge, batch where possible), embed. Defaults and the benchmark evidence behind them are in `docs/ARCHITECTURE.md`; small models that score poorly on agentic finance tasks (e.g. Claude Haiku 4.5) are excluded.
- **Judge independence**: the eval judge must come from a different model family than the generator under test.
- **Adapters**: OpenAI-compatible (covers OpenAI, OpenRouter and most open-weight hosts), Anthropic, Gemini.
- **Data classes**: `public` (KAP/news text), `internal` (run metadata, budgets), `sensitive` (anything user-identifying — not collected in this project). Each provider entry declares allowed classes and hosting region. First-party China-hosted APIs are allowed only for `public`; `internal` requires US/EU hosting.
- **Guards**: per-run token budget, global daily USD cap, response cache in Valkey, prompt caching where supported.
- Prices live in `config/router/prices.yaml`, not code.

## Consequences
+ Swap models by config; evals compare routes on cost and quality.
+ A concrete, explainable data-residency story for interviews.
− We maintain adapters ourselves (small, well-tested surface).
