---
name: ci-action-shas
description: How to resolve and which commit SHAs were pinned for GitHub Actions used in .github/workflows/ci.yml (M0)
metadata:
  type: reference
---

CLAUDE.md/task rules require pinning third-party GitHub Actions by full commit SHA
with a version comment, never inventing a SHA. Resolve them with:

```
git ls-remote https://github.com/<org>/<repo> refs/tags/<vX>*
```

(For `gradle/actions`, it's a monorepo — the tag is on the whole repo, e.g. `v6.3.0`,
and the action path is `gradle/actions/setup-gradle@<sha>`.)

**Annotated tags need peeling — this bit us once.** `git ls-remote ... refs/tags/vX.Y.Z`
can return the SHA of the **tag object**, not the commit, if the tag is annotated (you
can tell because a second line `refs/tags/vX.Y.Z^{}` also appears). GitHub Actions
`uses: owner/repo@<sha>` expects a **commit** SHA; a tag-object SHA happens to often
still resolve (GitHub's API accepts it) but is not what "pin by commit" conventionally
means and a stricter checker (or a security-auditor reviewer) will flag it. Always
resolve with the peeled ref to get the real commit:
```
git ls-remote https://github.com/<org>/<repo> 'refs/tags/vX.Y.Z^{}'
```
If no `^{}` line appears at all, the tag is lightweight and the plain tag SHA is
already a commit SHA. `gradle/actions` and `pnpm/action-setup` tags are annotated;
`actions/checkout`, `actions/setup-java`, `actions/setup-node`, `actions/upload-artifact`
were lightweight as of the versions below.

SHAs pinned as of 2026-09-28 for M0 CI (`.github/workflows/ci.yml`), all peeled to
commits and re-verified after the annotated-tag fix:
- `actions/checkout` v5.1.0 → `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09`
- `actions/setup-java` v6.0.1 → `de7274f081f381c8f8158605e0321c36c376e2e6`
- `gradle/actions/setup-gradle` v6.3.0 (annotated tag) → `9c971963bec38e04b3d30dcc455b5382be2fdbfb`
- `pnpm/action-setup` v6.1.0 (annotated tag) → `ea17c68df8912ef543352723c149a84f56e3d413`
- `actions/setup-node` v7.0.0 → `820762786026740c76f36085b0efc47a31fe5020`
- `actions/upload-artifact` v7.0.1 → `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`

**Why:** these expire in relevance once newer minor/patch tags ship — re-resolve via
`git ls-remote` rather than trusting this list blindly next time CI actions need a
bump; this file is just so the *method* and *last known good set* are recorded, not a
promise these are still current.

**How to apply:** when adding/bumping an action in any Saiman workflow, use
`git ls-remote` (no network install needed, plain git) to get the real SHA — never
guess or reuse a SHA from memory of another project.
