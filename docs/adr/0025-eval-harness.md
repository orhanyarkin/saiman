# ADR-0025: Eval harness

Status: Accepted (2026-10-05). Builds on ADR-0003 (judge independence), ADR-0010 (frozen corpus), ADR-0012 (retrieval).

## Context
M6 needs a published, repeatable measure of RAG quality and cost, including whether "latest disclosures" questions return the newest documents. The corpus is a frozen 2023 snapshot, so "latest" means latest in the corpus.

## Decision
- **Golden set** `services/evals/src/main/resources/golden/golden-set.v1.yaml`: metadata only (disclosure indexes, dates, short facts). No KAP text is committed (rule 8). About 30 items: 20 `RETRIEVAL` (one per ticker), 3 `FRESHNESS` (incl. "THYAO son özel durum açıklamaları"), 5 `ANSWER`, 2 `UNANSWERABLE`. Labels are made once with documented SQL against the local ingest DB; the file records `corpusVersion` and the run warns when it is stale.
- **Metrics at disclosure level** (chunks deduplicated by the `kap:<index>:` prefix, first rank kept). RETRIEVAL: Recall@10, MRR@10, graded nDCG@10. FRESHNESS with L = the 5 newest disclosures of the ticker: `recency@5 = |top5 ∩ L| / 5`, `latestHit@5`, nDCG@10 with gain `6 − rank` for members of L. ANSWER/UNANSWERABLE (deterministic, no judge): citation validity, citation recall, required-fact match, refusal correctness. Targets are reported, not CI-gating.
- **Recency leg in ingest.** RRF has no recency signal today. Recency intent is detected in code (`son`, `en son`, `güncel`, `yeni`, `latest`, `recent`) and adds a third RRF leg: the newest INDEXED documents for the tickers by `published_at DESC`. No change to the `libs/shared` retrieval contract. The report shows before/after for every kind; the leg must not hurt RETRIEVAL.
- **Two tiers.** Tier R (default `make eval`): `POST ingest /internal/v1/retrieve`, cost ≈ embeddings only. Tier A (`make eval EVAL_ANSWERS=1`): `POST seller-api /internal/v1/eval/questions`, requires `SERVICE_evals`, runs the same answer service (real prompt, parser, citation rebuild) without x402, guarded by a run guard (caller `evals`, 1 in flight, 60/h) and the router day cap. It never settles or records a payment.
- **No LLM judge, no cross-provider route comparison in M6.** ADR-0003 needs a different model family for a judge and that needs new paid provider keys (stop-and-ask). PLAN is updated; add providers later only with the human's go.
- **Run and output.** `evals` is a compose one-shot (profile `evals`, on the compose network so Host allowlists hold; only secret: its service token; only writable mount `build/evals`). Reports: `docs/evals/latest.md`, `latest.json`, `runs/<date>-<sha>.json` (corpus version, git sha, models, cost).

## Consequences
+ Free, repeatable, committed numbers; measures the product's real prompt.
− An unpaid internal path to an LLM function exists (bounded by service token, run guard and day cap; same trust level as ingest's `/internal/v1/retrieve`). Labels must be redone after re-ingest.

## Alternatives
Evals paying x402 itself (needs the buyer key, breaks K1, needs a full spend guard); copying the prompt into evals (measures a copy); LLM-as-judge now (provider approval).
