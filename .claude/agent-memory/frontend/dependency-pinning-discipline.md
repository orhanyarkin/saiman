---
name: dependency-pinning-discipline
description: Two corrections from the M0 web/ review on how to handle npm dependency-version problems — supply-chain policy and cross-package type conflicts
metadata:
  type: feedback
---

**Never add entries to `minimumReleaseAgeExclude` in `pnpm-workspace.yaml`, even if pnpm
auto-writes them.** pnpm 12's non-strict default silently appends packages there when `pnpm
install` picks a version published very recently (a supply-chain guard against just-published,
not-yet-vetted releases) — it happened for 4 TanStack packages published the same day I first ran
`pnpm install` for M0. The reviewer flagged this as a **blocking** finding, not a nit: an
auto-written exclude list is a silent policy bypass future installs would inherit without anyone
deciding to allow it. **Fix pattern**: pin the dependency to a slightly older version that already
clears the age threshold (check what the newer version resolves its own sub-dependencies to, and
match those too — e.g. `@tanstack/router-plugin` pins its `@tanstack/router-core` /
`router-generator` versions, so going one patch back on the top-level package had to go back on
those transitively-resolved versions too, which only shows up via `npm view <pkg>@<version>
dependencies`). Then replace whatever `pnpm-workspace.yaml` content resulted with an explicit
`minimumReleaseAge: <minutes>` value (I used 1440 = 24h) — an explicit value puts pnpm in *strict*
mode, so future auto-excludes aren't silently written; a fresh `install --frozen-lockfile` failing
loudly is the correct behavior if this happens again, not a silent bypass.

**When two packages' `.d.ts` files conflict after a major-version bump (e.g. declaration-merging
arity mismatch), prefer `skipLibCheck: true` over downgrading one of the packages** — but only
after positively confirming the types you actually depend on still work with a deliberate-typo
check (see [[web-stack-pins]] for the concrete vitest/jest-dom example). Downgrading avoids the
symptom but leaves the project on a stale major version for no real reason; `skipLibCheck` is also
already what Vite's own `react-ts` template ships as the default, so it's not a surprising
addition. The trade-off worth remembering: `skipLibCheck` stops checking *all* third-party
`.d.ts` files, not just the conflicting one, so don't reach for it to silence an error without
verifying the specific types you need are still sound.
