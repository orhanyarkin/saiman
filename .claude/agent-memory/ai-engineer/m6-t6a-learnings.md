---
name: m6-t6a-learnings
description: M6 T6a learnings - worktree sandbox quirks, ingest as app role, recency leg weight findings, corpus facts
metadata:
  type: project
---

- Worktree sandbox rejects compound bash (heredoc + python, `eval`/`github` in paths, loops). Write a script file with the Write tool and run `python3 <path>`; use unique scratchpad file names (the scratchpad is shared with other agents and was overwritten once).
- Ingest tests run as `ingest_app` (no TRUNCATE): reset with `DELETE FROM source_document` (chunks cascade), dead_letter, source_cursor.
- Corpus: only 16 of 20 configured tickers have indexed disclosures (no AKBNK/GARAN/ISCTR/YKBNK). Published date is usually NOT in chunk text (only in metadata).
- Plain RRF recency leg (weight 1) barely helps freshness (recency@5 0.07 -> 0.20); weight 3 -> 0.73 (SQL replay, not live). Chunk-level top-10 limits how many newest docs surface.
- Baseline RETRIEVAL (golden v1): recall@10 0.927, MRR 0.911, nDCG 0.870.
