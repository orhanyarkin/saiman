# evals

Golden-set evaluation of the RAG stack (ADR-0025). A Spring Boot CLI (no web server): it runs once, writes a report and exits.

**Tier R** (this module today, nearly free): for each RETRIEVAL and FRESHNESS item, `POST ingest /internal/v1/retrieve` with the item's ticker, reduce the returned chunks to disclosures (`kap:<index>:` prefix, first rank kept) and score:

| Kind | Metrics (disclosure level) |
|---|---|
| RETRIEVAL | Recall@10, MRR@10, graded nDCG@10 (grade 3 = the disclosure asked for, 2 = same subject) |
| FRESHNESS | with L = the 5 newest indexed disclosures of the ticker: recency@5 = \|top5 ∩ L\| / 5, latestHit@5, nDCG@10 with gain 6 − rank for members of L |

ANSWER and UNANSWERABLE items are loaded and validated but scored by the answer tier (T6b).

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
| `saiman.evals.seller.base-url`, `seller.service-token` | empty | answer tier (T6b); the token comes from the file secret `saiman.evals.seller.service-token` through `configtree` |
| `saiman.evals.answers.enabled` / `max-questions` / `stop-on-cap` | `false` / `30` / `true` | answer tier (T6b) |

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
