---
name: m5-ledger-views-conventions
description: M5 T3c conventions for the ledger/reconciliation/revenue screens, run-page ledger panel, fixture server ledger stand-in, e2e live config and the Lighthouse gate
metadata:
  type: project
---

- **gen:api** is `scripts/gen-api.mjs` (loops both contracts). A `a && b` npm script would pass
  `--check` to the last command only, so never go back to a chain.
- **BigInt money**: ledger `TrialBalanceRow` amounts are JSON numbers; unsafe integers (> 2^53)
  are already corrupted by `JSON.parse`. `checkTrialBalance` (lib/ledger-model.ts) marks a
  (book, asset) group `unverifiable` instead of claiming balance; safe-integer sums are exact BigInt
  and are rendered by `formatBigMoney`.
- **Run page "In the ledger"**: armed from event timestamps (`ledgerArmTime` = latest PAYMENT_* or
  terminal event), not from component state, because react-hooks 7 forbids `Date.now()` in render
  and setState-in-effect. `refetchInterval` (queries.ts) decides polling from `ledgerPollPhase`;
  the component only uses `useNow` to flip to the "gave up" text. Entries count is derived from the
  book states (the API has no count).
- **Reconciliation polling**: only while status RUNNING. PARTIAL is a FINISHED run (some items
  skipped), despite the task text; do not poll it.
- **Fixture server** serves ledger stand-ins: `capture.ledger.example.json` (merged with the
  orchestrator example in main()), plus stateful overlays: a settled payment appears in
  `ledger/payments?runId=` `ledgerLagMs` (800) after PAYMENT_SETTLED; scripted reconciliation POST
  (202, 409 while running, 429 + Retry-After inside the cooldown). The reconciliation POST state is
  global, so keep every "Run now" assertion in ONE e2e test.
- **Lighthouse**: `pnpm lighthouse` = build + `scripts/lighthouse.mjs` (fixture server + vite preview
  on 4120/4121, Playwright's Chromium via chrome-launcher). Reports in gitignored `reports/`.
- **e2e:live**: `playwright.live.config.ts` (no webServer, baseURL `SAIMAN_E2E_BASE_URL`, retries 0);
  the normal config has `testIgnore: "**/live/**"`.
- ESLint `react-refresh/only-export-components` applies to component files: keep helpers like
  `shortId` in `lib/`, not exported next to a component.
