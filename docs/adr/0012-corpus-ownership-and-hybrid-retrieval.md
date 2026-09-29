# ADR-0012: `ingest` owns the corpus and serves hybrid retrieval

Status: Accepted (2026-09-29)

## Context
ADR-0001 gives each service its own schema with no cross-schema queries, and seller-api needs cited answers over the KAP corpus (ADR-0010).

## Decision
- **Ownership.** `ingest` owns the `ingest` schema and exposes an **internal** retrieval API (`POST /internal/v1/retrieve`, `GET /internal/v1/chunks/{chunkId}`, `GET /internal/v1/tickers`; contract records in `libs/shared`). seller-api calls it over `RestClient`. It is reachable only on the compose network and `127.0.0.1` and must never sit behind the public load balancer (it would give paid content away).
- **Writes** go through Spring AI `PgVectorStore` (`ingest.chunk`, `vector(1536)` for `text-embedding-3-small`, HNSW cosine index, `idType(TEXT)`); **reads** use `JdbcClient`.
- **Idempotency without an outbox.** `(source, external_id)` is unique; a document whose `content_hash` is unchanged is skipped *before* embedding (`PgVectorStore.add` always embeds); chunk ids are deterministic (`kap:<index>:<nnnn>`) so a crashed run re-upserts safely; the embedding call happens outside the database transaction. Corrections/cancellations (`disclosureReason` `CORR`/`UPD`/`CANC` with `relatedDisclosureIndex`) mark the earlier disclosure superseded and it drops out of retrieval.
- **DLQ** is a table (`ingest.dead_letter`): a document failing 3 attempts is parked with its stage and error class; `make ingest-retry-dlq` resets it.
- **Hybrid retrieval.** Vector leg (top 40, ticker filter, `hnsw.iterative_scan = relaxed_order` so filtered HNSW returns enough rows) and a Turkish full-text leg (`to_tsvector('turkish', …)` generated column with GIN, `websearch_to_tsquery`, both sides lower-cased with the `tr-TR-x-icu` collation so `I`/`ı` and `İ`/`i` match), fused with **RRF (k = 60) in Java** (a pure, unit-tested function).
- **No Kafka events in M2.** `ingest.document-indexed.v1` has no consumer yet; the outbox/inbox pattern (CLAUDE.md rule 5) arrives with M4 when the ledger becomes the first consumer.
- Ingest is an **on-demand, resumable job** (the corpus is static): `saiman.ingest.backfill.enabled=true` at startup or `make ingest-backfill`; a Postgres advisory lock prevents two runs.

## Consequences
+ No cross-schema access; the retrieval contract is versioned in one place.
− One more internal HTTP hop; the query embedding is a paid call, cached by seller-api per `(ticker, corpusWatermark)` for summaries.
− The embedding dimension is fixed by the schema: changing the model means re-embedding.
Revisit if the corpus grows past ~1M chunks or retrieval needs to run inside seller-api.
