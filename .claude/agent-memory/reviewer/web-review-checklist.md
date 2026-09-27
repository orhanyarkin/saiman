---
name: web-review-checklist
description: Verified pnpm 12 / Vitest / TanStack Router pitfalls for Saiman web reviews (release-age exclusions, jest-dom + Vitest 5 types, generated route tree)
metadata:
  type: project
---

Verified on 2026-09-27 (M0 T5 review) with pnpm 12.6.0, in a scratchpad copy of web/:

- **pnpm minimumReleaseAge.** Built-in default is 1440 min and NON-strict: pnpm silently appends
  immature versions to `minimumReleaseAgeExclude` in pnpm-workspace.yaml, and CI's frozen install
  then trusts them. Setting `minimumReleaseAge: 1440` explicitly makes it strict (install fails
  instead). A frozen install re-verifies lockfile entries against the cutoff. Flag any exclude
  entry; the fix is pinning the previous release. Path: pin in package.json, install WITH the old
  excludes still present, then replace the file with `minimumReleaseAge: 1440`.
  Check dates with `pnpm view <pkg> time --json`.
- **Vitest 5 + @testing-library/jest-dom 7.0.1**: `tsc -b` fails with TS2428 ("All declarations
  of 'Assertion' must have identical type parameters"). Runtime tests pass. `skipLibCheck: true`
  (the Vite template default) fixes it, and matcher typing still works (typos are still rejected).
- **TanStack router-plugin** writes `src/routeTree.gen.ts` (must be committed, or `tsc -b` in
  `build` fails before Vite runs) and a `.tanstack/tmp` dir, which should be gitignored.
- Vite statically replaces `import.meta.env.VITE_*`, so a `!== "true"` early return tree-shakes
  OTel out of builds where it is disabled (verified 322 kB vs 394 kB).

**How to apply:** on any web/ diff, run lint/typecheck/test/build in a scratchpad copy (never in
web/ itself, since build rewrites routeTree.gen.ts and dist) and grep pnpm-workspace.yaml for
excludes. See also [[review-gotchas-infra-ci]] for the pnpm/action-setup `package_json_file` gap.
