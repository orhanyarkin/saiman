---
name: review-gotchas-infra-ci
description: Recurring infra/CI/tracing defects found in Saiman reviews (M0 T1) — check these first on any compose, Makefile, workflow or trace-verification change
metadata:
  type: project
---

Checklist of defects found in M0 T1 review (2026-09-27); verify each again on similar diffs.

- **datasource-micrometer span names**: Micrometer Tracing uses the observation's *contextual* name, so JDBC spans appear as `connection` / `query` / `result-set`, not `jdbc.*`; no `db.system` attribute. Attribute keys are `jdbc.*` (e.g. `jdbc.datasource.name`, `jdbc.query[0]`). Scripts/tests asserting `jdbc.query` span names are wrong unless they were checked against real output (the infra agent tested verify-trace with hand-made synthetic spans named `jdbc.query`).
- **SHA-pinned actions**: verify with `git ls-remote <repo> refs/tags/vX refs/tags/vX^{}`; annotated tags (gradle/actions, pnpm/action-setup) have a tag-object SHA and a peeled commit SHA — pin the `^{}` commit.
- **pnpm/action-setup** reads `packageManager` from root `package.json` by default; the web app lives in `web/`, so it needs `package_json_file: web/package.json`. `defaults.run.working-directory` does not apply to `uses:` steps.
- **Compose env_file ../../.env** injects the host-oriented `.env.example` values (localhost URLs, all API keys, buyer private key) into every app container; `environment:` only wins for keys it sets.
- **Makefile default goal**: first rule becomes the default; a file rule like `web/node_modules` first means bare `make` runs pnpm install.
- **Redpanda listeners**: the host-published port must map to the listener that advertises `localhost`, matching `.env.example`.
- Collector 0.161 component names: `otlp_grpc`, `otlp_http` (old `otlp`/`otlphttp` are deprecated aliases).

**Why:** these passed `docker compose config`, `bash -n` and `make -n` but would fail at acceptance or in CI.
**How to apply:** grep for these patterns first when reviewing infra/CI/trace changes; confirm library behaviour from the jar in ~/.gradle caches rather than from design docs.
