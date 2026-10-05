# Eval results

Method and golden set: `services/evals/README.md`, ADR-0025. Reports from `make eval` land here as `latest.md` / `latest.json` (`runs/` keeps history); this file records the numbers that drove the recency-leg decision.

## Retrieval baseline, golden set v1 (2026-10-05)

Corpus `24303757d747de77` (frozen 2023 KAP snapshot, 16 tickers with indexed disclosures), ingest as running before the recency leg (two legs: vector + Turkish full text, RRF k=60), topK 10 chunks, ticker filter on, 19 queries. Disclosure level.

| Kind | n | Metric | Baseline |
|---|---:|---|---:|
| RETRIEVAL | 16 | Recall@10 | 0.927 |
| RETRIEVAL | 16 | MRR@10 | 0.911 |
| RETRIEVAL | 16 | nDCG@10 | 0.870 |
| RETRIEVAL | 16 | p95 latency | 1576 ms (first query after idle; the rest 210-440 ms) |
| FRESHNESS | 3 | recency@5 | 0.067 |
| FRESHNESS | 3 | latestHit@5 | 0.333 |
| FRESHNESS | 3 | nDCG@10 | 0.181 |

Per item: "THYAO son özel durum açıklamaları" returns five 2023 spring/summer disclosures (none of the five newest, which are all from 29 and 15 December); "ASELS en son açıklamaları" 0 of 5. Weakest RETRIEVAL items: TCELL (recall 0.33; three `Finansal Duran Varlık Edinimi` disclosures, only the primary one found), SISE (0.5) and ARCLK (MRR 0.25; the committee disclosure is rank 4 behind other March 2023 disclosures).

## Recency leg (ingest `HybridRetriever`)

A question with recency intent (`son`, `en son`, `güncel`, `yeni`, `latest`, `recent`, whole words, Turkish-locale case folding) **and** a ticker filter adds a third RRF leg: the first chunk of each of the 40 newest indexed documents of those tickers, ranked by `published_at DESC`. Its term is `RECENCY_WEIGHT / (60 + rank)`.

**RETRIEVAL cannot regress by construction**: no RETRIEVAL question of the golden set contains an intent keyword, and without intent (or without tickers) the fused list is the exact two-leg result (`RetrievalTests.withoutRecencyIntentOrWithoutTickersTheResultIsTheTwoLegResult`, `RrfFusionTests.emptyRecencyLegGivesExactlyTheTwoLegResult`). So the RETRIEVAL numbers above are also the "after" numbers.

**FRESHNESS, estimated.** The running ingest container is the old image, so the "after" for FRESHNESS is an SQL replay, not a live run: the lexical and recency legs were executed read-only against the live corpus with ingest's own queries, the vector ranks were taken from the baseline responses (topK 20; chunks the old response did not return contribute no vector term). Mean over the 3 FRESHNESS items:

| Recency weight | recency@5 | latestHit@5 | nDCG@10 |
|---|---:|---:|---:|
| no leg (baseline) | 0.067 | 0.333 | 0.181 |
| 1 | 0.200 | 0.333 | 0.294 |
| 2 | 0.600 | 0.667 | 0.805 |
| **3 (shipped)** | **0.733** | **0.667** | **0.852** |
| 6 | 0.800 | 0.667 | 0.897 |

Why a weight: with weight 1 the leg's best term (1/61) cannot lift a newest disclosure that neither other leg ranks above the fused top 10, so "ASELS en son açıklamaları" stays at 0. The weight only applies when the user explicitly asked for the newest. Weight 3 was picked as the knee of the curve on three items; the replay is optimistic about the vector leg and the set is tiny, so treat 0.73 as a hypothesis and **re-run the live eval after rebuilding ingest** (below). THYAO's newest disclosure still misses the top 10 at weights up to 6: other chunks of the December disclosures that match "özel durum açıklamaları" in both legs fill the ten chunk slots.

### Reproduce the "after" run

```bash
./gradlew :services:ingest:bootBuildImage     # new ingest image (V3 grants need the db-init roles of T4)
docker compose -f deploy/compose/docker-compose.yml --profile apps up -d ingest
SAIMAN_EVALS_INGEST_BASE_URL=http://127.0.0.1:8083 SAIMAN_EVALS_OUTPUT_DIR=build/evals \
SAIMAN_EVALS_LABEL=after GIT_SHA=$(git rev-parse --short HEAD) ./gradlew :services:evals:bootRun
```

Diff `build/evals/latest.json` against the baseline run file; RETRIEVAL must be identical.

## Tier A: answers (how to run and read it)

Off by default; `make eval` stays a free retrieval run and its `latest.md` says "Tier A not run". To score answers:

```bash
make up                 # apps healthy, incl. seller-api and the evals service token secret
make eval EVAL_ANSWERS=1
```

This calls the seller's internal eval endpoint once per ANSWER/UNANSWERABLE item (7 in golden set v1, sequentially, at most 30), spends model money through the router (about $0.06 per 30 questions, bounded by the $0.70 day cap), and adds a "Tier A: answers" section to `latest.md`/`latest.json`: outcome counts, per-kind task success / fact recall / citation recall / citation validity / refusal correctness, per-item rows, total and per-question USD, p95 latency. Scoring is deterministic (no LLM judge); definitions are in `services/evals/README.md`.

Reading the numbers: *task success* needs the right fact, every expected source cited and every citation valid, so one missed source fails the item; fact recall and citation recall show which half failed. *Citation validity* below 1.0 means the model cited a disclosure that retrieval does not return for that question. The ANSWER items are publication-date questions and the model receives each excerpt's publication timestamp from document metadata, so a high fact recall shows the metadata is used, not that dates are read from the disclosure text. If `stoppedOnCap` is set, the day cap ended the run: rerun after the cap resets.
