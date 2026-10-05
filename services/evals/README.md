# evals

Golden-set evaluation of the RAG stack (ADR-0025). A Spring Boot CLI (no web server): it runs once, writes a report and exits.

**Tier R** (this module today, nearly free): for each RETRIEVAL and FRESHNESS item, `POST ingest /internal/v1/retrieve` with the item's ticker, reduce the returned chunks to disclosures (`kap:<index>:` prefix, first rank kept) and score:

| Kind | Metrics (disclosure level) |
|---|---|
| RETRIEVAL | Recall@10, MRR@10, graded nDCG@10 (grade 3 = the disclosure asked for, 2 = same subject) |
| FRESHNESS | with L = the 5 newest indexed disclosures of the ticker: recency@5 = \|top5 ∩ L\| / 5, latestHit@5, nDCG@10 with gain 6 − rank for members of L |

ANSWER and UNANSWERABLE items are scored by Tier A (below) when it is switched on; otherwise they are only loaded and validated, and the report says "Tier A not run".

## Tier A: answers (optional, spends model money)

`make eval EVAL_ANSWERS=1` (compose one-shot) or `SAIMAN_EVALS_ANSWERS_ENABLED=true` with `bootRun`. For each ANSWER and UNANSWERABLE item (file order, at most `answers.max-questions`, default 30; the set has 7) the runner calls, one at a time, `POST seller-api /internal/v1/eval/questions` with `{ticker, question}` and the evals service token as `Authorization: Bearer`. The endpoint runs the real answer service (prompt, parser, citation rebuild) without x402 and never settles; its run guard allows 1 call in flight and 60 per hour, and the router day cap applies. **No LLM judge** (ADR-0003/0025): every score is a pure function (`AnswerScoring`, hand-computed unit tests).

| Number | Definition |
|---|---|
| outcome counts | ANSWERED (>= 2 valid citations), NO_VALID_CITATIONS (model ran, fewer than 2), REFUSED (before any model call), LLM_CAP, ERROR, plus CALL_FAILED for an HTTP-level failure of one question |
| citation validity | share of returned citations whose chunk id is `kap:<index>:<nnnn>` **and** whose disclosure index is among the disclosures `POST ingest /internal/v1/retrieve` returns for the same question and ticker (top-k as configured; basis `RETRIEVAL`). If that retrieval fails, the fallback basis `GOLDEN_INDEXES` accepts any index that appears anywhere in the golden set (basis shown per item) |
| citation recall (ANSWER) | cited disclosures that are in `expected.sources`, over `expected.sources` |
| fact recall (ANSWER) | `requiredFacts` entries with at least one accepted spelling in the answer, over all entries. Both sides are normalised: Turkish-locale lower-casing, diacritics folded, dates to `yyyy-MM-dd` (`15.11.2023`, `15/11/2023`, `15 Kasım 2023`, ISO timestamps), percentages to `12.5%` (`%12,5`, `yüzde 12,5`); digits must not match inside longer numbers |
| task success (ANSWER) | outcome ANSWERED and all facts and all expected sources found and every citation valid; LLM_CAP/ERROR items are not scored |
| refusal correct (UNANSWERABLE) | REFUSED or NO_VALID_CITATIONS is correct; ANSWERED is wrong; LLM_CAP/ERROR not scored |
| cost | sum of the seller-reported `modelCostUsdMicros` (integer micro-USD) and the mean per question that got a response; formatted as USD only in the report |
| p95 latency | nearest rank over per-question wall time, retries included |

Expect about **$0.06 for 30 questions** (about 9 % of the $0.70 router day cap); the golden set has 7 such items, so a full Tier A run costs a few cents.

**Caveat for the date questions.** The ANSWER items ask for publication dates. The answer service puts each excerpt's `published` timestamp (document metadata, Europe/Istanbul offset) into the model context, so the model reads the date from metadata, not from the disclosure text. The report repeats this next to the table.

Failure handling: 401/403 (bad service token) abort the tier with a clear message and no retry; 429 (run guard limit) and 503 (guard undecidable) abort and are not recorded as outcomes; LLM_CAP ends the tier early when `answers.stop-on-cap` (the remaining items are `NOT_RUN`); other 4xx are recorded per item without retry; connect failures and 5xx are retried (bounded, jittered), a read timeout is not (the seller may still be running the model). Redirects are never followed, so the token cannot leave the configured host. An aborted tier or any ERROR/CALL_FAILED item makes the process exit non-zero after the report is written. With `answers.enabled=true`, a blank or malformed token, or a missing/invalid `seller.base-url`, stops the application at start; the token is stripped of whitespace, checked against `[A-Za-z0-9_-]{32,128}`, redacted in `toString` and never logged or written to the report.

## Run

```bash
# against the local stack (ingest publishes 127.0.0.1:8083; its /internal Host allowlist accepts 127.0.0.1)
SAIMAN_EVALS_INGEST_BASE_URL=http://127.0.0.1:8083 SAIMAN_EVALS_OUTPUT_DIR=build/evals \
GIT_SHA=$(git rev-parse --short HEAD) SAIMAN_EVALS_LABEL=after ./gradlew :services:evals:bootRun
```

Output: `latest.md`, `latest.json` and `runs/<date>-<sha>[-<label>].json` in `saiman.evals.output-dir` (default `/out`; compose mounts `build/evals`). The process exits non-zero when any query failed (the report is still written).

| Key (env) | Default | Meaning |
|---|---|---|
| `saiman.evals.run-on-startup` | `true` (tests: `false`) | run on start |
| `saiman.evals.golden-set` | `classpath:golden/golden-set.v1.yaml` | golden set location |
| `saiman.evals.output-dir` | `/out` | report directory |
| `saiman.evals.label` | empty | free text in the header and run file name (`baseline`, `after`) |
| `saiman.evals.git-sha` (`GIT_SHA`) | `unknown` | commit under test |
| `saiman.evals.ingest.base-url` | `http://localhost:8083` | ingest |
| `saiman.evals.ingest.read-timeout` / `retry-attempts` / `retry-wait` | `20s` / `3` / `500ms` | per-attempt timeout; retries on transport errors and 5xx |
| `saiman.evals.retrieval.top-k` | `10` | chunks requested per query |
| `saiman.evals.seller.base-url`, `seller.service-token` | empty | Tier A; the token comes from the file secret `saiman.evals.seller.service-token` through `configtree` |
| `saiman.evals.seller.connect-timeout` / `read-timeout` / `retry-attempts` / `retry-wait` | `5s` / `90s` / `2` / `1s` | Tier A HTTP behaviour |
| `saiman.evals.answers.enabled` / `max-questions` / `stop-on-cap` | `false` / `30` / `true` | Tier A on/off, question limit, stop at the day cap |

Cost of a Tier R run: one short embedding per query, 19 queries, about $0.00002.

## The golden set

`src/main/resources/golden/golden-set.v1.yaml`: metadata only (disclosure indexes, dates, titles as labels). **No KAP text is committed.** The header records the corpus the labels belong to:

```yaml
corpus: {watermark: 2023-12-29T20:46:52Z, corpusVersion: "24303757d747de77"}
```

When ingest reports another `corpusVersion`, the run logs a warning and the report carries a "relabel" banner (the scores may be meaningless). 26 items: 16 RETRIEVAL (one per ticker with indexed disclosures; AKBNK, GARAN, ISCTR and YKBNK have none in the free MKK feed, so there is nothing to label), 3 FRESHNESS (including "THYAO son özel durum açıklamaları"), 5 ANSWER, 2 UNANSWERABLE.

### How labels are made

Run the queries below read-only against the ingest schema (`docker compose exec postgres psql -U saiman -d saiman` from `deploy/compose`, or `make psql` if it exists). All indexes are `source_document.external_id`; only `status = 'INDEXED'` documents are retrievable, so only those are labels.

**RETRIEVAL.** Each item names a ticker, a disclosure *title* and a month (`label.title`, `label.primaryMonth`). Pick topics with few instances so the question is answerable by a ranking:

```sql
-- candidate (ticker, title) pairs with 1-4 instances, with their dates
SELECT ticker, title, count(*) AS n,
       string_agg(external_id || '@' || to_char(published_at AT TIME ZONE 'Europe/Istanbul', 'YYYY-MM-DD'),
                  ', ' ORDER BY published_at) AS disclosures
FROM ingest.source_document
WHERE status = 'INDEXED' AND disclosure_class = 'ODA'
GROUP BY ticker, title HAVING count(*) BETWEEN 1 AND 4
ORDER BY ticker, n, title;
```

The question is written from the title and the month of the disclosure you want ("SISE esas sözleşme tadili bildirimi Şubat 2023"). Avoid the recency words `son`, `en son`, `güncel`, `yeni`, `latest`, `recent` in RETRIEVAL questions: they switch the ingest recency leg on, which is meant for FRESHNESS questions. Then derive `expected.relevant`:

```sql
-- grade 3 = published in label.primaryMonth, grade 2 = the same title in any other month
SELECT external_id AS index,
       CASE WHEN to_char(published_at AT TIME ZONE 'Europe/Istanbul', 'YYYY-MM') = :'primary_month' THEN 3 ELSE 2 END AS grade
FROM ingest.source_document
WHERE status = 'INDEXED' AND ticker = :'ticker' AND title = :'title'
ORDER BY grade DESC, published_at DESC;
```

**FRESHNESS.** `expected.latest` is the five newest indexed disclosures of the ticker, newest first (ties broken by id, as ingest's recency leg does):

```sql
SELECT external_id FROM ingest.source_document
WHERE status = 'INDEXED' AND ticker = :'ticker'
ORDER BY published_at DESC, id LIMIT 5;
```

**ANSWER.** `expected.sources` is the disclosure the question is about; `requiredFacts[].anyOf` lists the spellings of the publication date (`to_char(published_at AT TIME ZONE 'Europe/Istanbul', 'DD.MM.YYYY')`, long form with the Turkish month name, ISO).

**UNANSWERABLE.** `NOT_IN_CORPUS` for a ticker without indexed disclosures (`SELECT count(*) FROM ingest.source_document WHERE ticker = 'AKBNK'` is 0), `AFTER_CORPUS_WATERMARK` for a period after the corpus watermark.

**corpus header.** Run one retrieval and copy the two fields (a single query, a fraction of a cent):

```bash
curl -s -X POST http://127.0.0.1:8083/internal/v1/retrieve -H 'Content-Type: application/json' \
  -d '{"query":"THYAO","tickers":["THYAO"],"topK":1}' | jq '{corpusWatermark, corpusVersion}'
```

### Relabel after a re-ingest

1. Run the "candidate" query; if a `label.title` has changed instances, re-run the RETRIEVAL SQL for every item with its `label.title`/`label.primaryMonth` and replace `expected.relevant`; if a primary disclosure vanished (blocked or superseded), choose another topic.
2. Re-run the FRESHNESS SQL for the three tickers.
3. Check each ANSWER `sources` index is still `INDEXED` (`SELECT status FROM ingest.source_document WHERE external_id = '...'`).
4. Update `corpus.watermark` and `corpus.corpusVersion` from the curl above, run the eval, commit the new `latest.*` next to the golden set change.

A bad edit fails fast: the loader reports every problem with its location (`items[3] (R-SISE).expected.relevant[1].grade: must be 1, 2 or 3`), rejects unknown keys and keys that do not belong to the item kind.
