# Saiman web

React 19 + Vite + strict TypeScript, TanStack Router/Query, Tailwind + shadcn/ui. Static build; no
Node server in production (ADR-0002).

## Commands

```bash
pnpm dev            # Vite dev server (proxies /api, see proxy.ts)
pnpm test           # Vitest + Testing Library
pnpm lint           # ESLint + Prettier check
pnpm typecheck
pnpm build
pnpm gen:api        # regenerate src/lib/api/generated/* from docs/api/*.openapi.json
pnpm gen:api --check  # fail if the checked-in types are stale
pnpm e2e            # Playwright against the fixture server (no stack needed)
pnpm lighthouse     # accessibility audit of the dashboard routes
```

## API token handling (ADR-0023)

The API needs a Bearer token. The SPA treats it as a secret:

- The token is kept **in memory only**. If the user ticks "keep for this tab" it is also written to
  `sessionStorage`, which the browser clears when the tab closes.
- It is **never** written to `localStorage` or cookies and **never** put in a URL (no query string,
  no hash), so it cannot leak through history, referrers or shared links.
- It is sent only as an `Authorization: Bearer` header (also on the fetch-based SSE stream), never
  logged, and dropped on a 401.
- `GET /api/v1/me` tells the UI who it is. READER tokens see no Start, Approve, Reject or
  Run-reconciliation controls and get a read-only note instead; the API enforces the same rule.
- In recorded-demo (replay) mode no token is needed and nothing can change state.

## Live e2e (`pnpm e2e:live`, `make e2e-live`)

The live specs in `e2e/live/` run against the real stack and spend testnet funds. With auth on they
need an **operator** token in a file named by `SAIMAN_E2E_TOKEN_FILE` (preferred; or the environment variable `SAIMAN_E2E_TOKEN`); each test seeds it into
the tab's `sessionStorage` before the app loads (the same path as `e2e/helpers.ts`). A missing
variable fails the run with a message naming the variable, never its value.

`make e2e-live` is expected to export it from the local secret file, without printing it, for
example:

```make
e2e-live: web/node_modules
	SAIMAN_E2E_TOKEN_FILE=secrets/api_operator_token SAIMAN_E2E_BASE_URL=http://localhost:8088 pnpm --dir web e2e:live
```

Make echoes the recipe text with `$$(...)` unexpanded, so the token itself never reaches the log.
