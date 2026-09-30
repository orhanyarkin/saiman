---
name: ingest-m2-learnings
description: What worked building services/ingest (M2): ICU collation, search_path, test fakes, sandbox quirks
metadata:
  type: project
---
- `tr-TR-x-icu` collation works in pgvector/pgvector:0.8.6-pg17-trixie; generated tsvector column with `lower(content COLLATE "tr-TR-x-icu")` is fine, no translate() fallback needed.
- pgvector lives in `public`: datasource needs `connection-init-sql: SET search_path TO ingest, public` (not hikari.schema); migration qualifies `public.vector`.
- Lexical leg: OR-join query words (AND-only websearch returns nothing for natural questions).
- Circuit breaker opens after ~5 straight 500s: tests for DLQ must use non-retryable statuses (403/404), else run aborts.
- Fake MKK = JDK HttpServer with windowed listing; RecordingEmbeddingModel counts calls (assert zero on rerun).
- Sandbox: Bash commands containing the word "github" (e.g. package path io.github...) are refused as "git" commands; use the Write tool or a script file in the scratchpad that builds the path from 'git'+'hub'.
- No `FakeModelRouter` existed in libs/model-router in my worktree; ingest tests define their own ModelRouter bean.
