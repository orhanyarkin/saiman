# ADR-0001: One PostgreSQL instance, schema per service, no separate NoSQL/vector DB

Status: Accepted

## Context
Services need relational data (ledger, spend control), vectors (RAG) and full-text search. Running cost must stay low: one small RDS instance on AWS, one container locally.

## Decision
One PostgreSQL 17 instance with pgvector. Each service owns its schema and its migrations; no cross-schema queries. Valkey handles ephemeral counters and caches. No MongoDB, no dedicated vector DB.

## Consequences
+ One backup, one operational surface, ~2 GB RAM total.
+ ACID transactions across ledger postings and outbox rows.
− Vector search scale is limited (fine up to millions of chunks with HNSW).
− Services share a failure domain; acceptable for this scale, documented in "how I'd scale this".
Revisit if: corpus > 10M chunks or write contention appears.
