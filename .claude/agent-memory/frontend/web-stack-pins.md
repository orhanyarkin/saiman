---
name: web-stack-pins
description: Exact pinned npm versions for web/ as of M0 (2026-09-28), and real compatibility bugs found while wiring them together
metadata:
  type: project
---

As of M0 (2026-09-28), `web/package.json` pins these exact versions (verified against npm registry on 2026-09-27, adjusted 2026-09-28 per review — see below):

React 19.3.0, Vite 8.3.1, TypeScript 5.9.3 (NOT 7.x — typescript-eslint 8.70.1 requires `<6.1.0`,
openapi-typescript needs `^5.x`), TanStack Router **1.170.39** + router-plugin **1.168.40**
(one patch older than the very latest — see the supply-chain policy note below; these resolve to
router-core 1.171.32 / router-generator 1.167.38), TanStack Query 5.104.0, Tailwind 4.3.3 via
`@tailwindcss/vite`, ESLint 10.11.0 + typescript-eslint 8.70.1, Prettier 3.9.9,
`@opentelemetry/sdk-trace-web` 2.11.0 family (api 1.9.1, resources 2.11.0, instrumentation-fetch
0.222.0, exporter-trace-otlp-http 0.222.0), `@testing-library/react` 16.3.3, jsdom 30.1.1,
`@playwright/test` 1.63.0, vitest **5.0.2**. No `lucide-react` — it was added speculatively for
shadcn's icon convention but never actually imported; removed. `components.json` still records
`"iconLibrary": "lucide"` as config for if/when the shadcn CLI is used to add more components.

**vitest 5.0.2 + `@testing-library/jest-dom` 7.0.1's `Assertion` interface arity mismatch: fixed
with `skipLibCheck: true`, not by downgrading vitest.** `@testing-library/jest-dom`'s `./vitest`
subpath declares `interface Assertion<T = any>` (one type param); vitest 5 changed its own
`Assertion` to `<R extends void | Promise<void> = void, T = unknown>` (two type params).
TypeScript's declaration-merging requires identical type-parameter lists, so importing
`@testing-library/jest-dom/vitest` with vitest 5 fails to typecheck with TS2428 ("All declarations
of 'Assertion' must have identical type parameters") — the error surfaces inside node_modules, not
at the call site, so a local `@ts-expect-error` can't fix it. I first "fixed" this by downgrading
to vitest 4.1.11 (matching single-param `Assertion`); reviewed and corrected: add
`"skipLibCheck": true` to `tsconfig.app.json` instead (the Vite react-ts template default anyway)
and keep vitest current. This resolves the conflicting `.d.ts` merge while still getting the real
typed matchers from jest-dom's JS-level `expect.extend()` — verified with a throwaway test file
containing both a real matcher call (`toBeInTheDocument()`, must compile) and a typo'd one
(`toBeInTheDocumentz()` behind `@ts-expect-error`, must itself fail as "unused directive" if the
typing were loose — it didn't, confirming the matchers are genuinely typed, not `any`). Caveat:
`skipLibCheck` skips *all* `.d.ts` checking repo-wide, so it can mask other genuinely-broken
third-party type declarations; the deliberate-typo check is what makes trusting it here safe. See
[[dependency-pinning-discipline]] for the general lesson.

**jsdom (30.1.1) does not implement `PerformanceObserver`.**
`@opentelemetry/instrumentation-fetch`'s `enable()` does
`typeof PerformanceObserver !== 'undefined'` once at *module load time* to decide it's running in
a real browser; if that's false it warns and silently never patches `fetch`. To unit-test the
traceparent header in jsdom, stub a minimal `class StubPerformanceObserver { observe(){}
disconnect(){} }` via `vi.stubGlobal("PerformanceObserver", ...)` **before** dynamically
`import()`-ing the module that pulls in instrumentation-fetch (static imports are hoisted, so the
stub has to exist before that module first evaluates — use `await import(...)` inside the test,
after the stub, and `vi.resetModules()` in afterEach). jsdom also lacks
`performance.getEntriesByType`/`clearResourceTimings`, but instrumentation-fetch already
guards those with `if (!performance.getEntriesByType) return;`, so no extra polyfill needed there.

See [[ui-conventions]] for how this plays out in the actual test files.
