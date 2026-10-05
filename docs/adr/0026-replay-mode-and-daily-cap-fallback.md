# ADR-0026: Replay mode and daily-cap fallback

Status: Accepted (2026-10-05). Implements ADR-0004's public replay demo; extends the capture format of ADR-0022.

## Context
The public demo is a static SPA replaying recorded runs (ADR-0004). The live backend also needs a defined behaviour when the global daily LLM cap is used up.

## Decision
- **Capture format v1:** `{schemaVersion:1, capturedAt, environment, network, sourceCommit, responses:{"<GET path+query>":body}, runEvents:{"<runId>":[Envelope]}}`.
- **Optional capture fields:** `corpus: {snapshotLabel, newestDisclosureAt}` (the frozen KAP snapshot date, shown in the replay banner) and `annotations: {"<runId>": {label, detail, tone: warning|info}}` (plain-text callouts on a run, e.g. a published failed run). Both are stored by `make capture-demo` (`CAPTURE_RUN_IDS`, `CAPTURE_ANNOTATIONS_FILE`, `CAPTURE_CORPUS_WATERMARK`); readers ignore malformed values.
- **SPA replay.** `VITE_DEMO_MODE=replay` builds an always-replay app; `VITE_REPLAY_CAPTURE_URL` (default `/demo/capture.json`). `apiGet` resolves from `responses` (missing path → "not in this recording"); `subscribeRunEvents` replays with the original gaps clamped to 50 ms-2 s; `/api/v1/me` answers READER; no token dialog, mutations hidden. A banner on every route: "Recorded on <environment>, <date> - Base Sepolia testnet. Nothing on this page is live."
- **Daily-cap fallback.** `ModelRouter.dailyCap()` returns `DailyCapStatus(spent, cap)` (fails closed). `POST /api/v1/runs` answers **503** `urn:saiman:problem:llm-daily-cap`, `code: LLM_DAILY_CAP_REACHED`, `replayAvailable: true`, `Retry-After` until 00:00 UTC, when the remaining cap is below the per-run LLM budget. A mid-run cap hit fails the run with the new `FailureCode.LLM_DAILY_CAP_REACHED` (distinct from `LLM_BUDGET_EXHAUSTED`). `GET /spend` gains `llmDay`. The live SPA offers "Open the recorded demo", switching to the replay source for the session.
- **`make capture-demo`** (bash + curl + jq): reader token via a 0600 header file, never argv; captures runs, run events, payments, approvals, spend, ledger, revenue, reconciliation; **scrubs before writing** and fails if `nonce`, `signature`, `paymentKey`, `privateKey`, `Bearer` or a 64-hex string that is not a tx hash appear. Publish with `CAPTURE_OUT=web/public/demo/capture.json`.
- Cloudflare deployment and the AWS capture session are human-only.

## Consequences
+ The cap becomes a visible, honest state instead of a failure. Replay needs no second data layer.
− A cap hit after the seller's upfront settle still yields a credit note (no pre-settle veto hook; THREAT_MODEL gap).

## Alternatives
Silent mock responses in the live backend (dishonest); a separate demo service (extra cost).
