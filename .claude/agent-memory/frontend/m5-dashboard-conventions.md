---
name: m5-dashboard-conventions
description: M5 web conventions (T3b) - generated API types, query keys, polling, e2e quirks, test helpers
metadata:
  type: project
---

- API types: `src/lib/api/types.ts` only aliases `components["schemas"][...]` from
  `src/lib/api/generated/orchestrator.ts` (`pnpm gen:api`, `pnpm gen:api --check`). Generated dir is
  in `.prettierignore` and the ESLint ignores (formatting it breaks `--check`). We kept `apiGet`/
  `apiPost` in `source.ts` (replay swap point) instead of `openapi-fetch` (not installed).
- Contract quirks: runs list `{items, next?}`, payments `{items}`, approvals bare array with
  `amountAtomic` plain number (wrap with `usdc()`), `@Nullable` = optional/`null`.
- Query keys: `['runs','list',limit]`, `['runs','recent']`, `['runs',id,'payments'|'summary'|'events']`,
  `['approvals','pending']` (poll 5 s), `['spend',day]`. `useDocumentTitle` prefixes `(n) Approval needed`.
- Default `retry` is 1 and never for 4xx (`main.tsx`), else error states take ~7 s to show.
- Approval decisions go through `lib/use-approval-decision.ts` (shared by card and list row); the
  decided row leaves the list because the pending query is invalidated.
- Component tests with `Link`: `src/test/render.tsx` `renderWithProviders` (memory router + client).
  jsdom `<meter>`: assert `.value` property, not `aria-valuenow`.
- e2e: Playwright webServers need `gracefulShutdown` or `pnpm e2e` never exits (vite grandchild).
  Kill leftovers by pid after checking `/proc/<pid>/cwd`; never `pkill -f` broad patterns. Fixture
  server gives each started run unique approval/intent ids; captured `/api/v1/spend` is matched by
  path (query ignored), runs list pages are exact `path?query` keys.
- Don't use heredoc-heavy compound bash lines in this sandbox (the harness refuses some); use the
  Write/Edit tools or short `python3 - <<EOF` calls.
