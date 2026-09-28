---
name: review-gotchas-infra-ci
description: Recurring infra/CI/tracing defects found in Saiman reviews (M0 T1, M1 T5) — check these first on any compose, Makefile, workflow or trace-verification change
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
- **Validation scripts echoing the bad value** (M1 T5): an env check that prints `'${value}'` on failure leaks a private key when the user pastes the wrong `cast wallet new` line (0x+64 hex). Never echo the rejected value; print its length/shape, and special-case 64-hex as "looks like a private key".
- **Standalone builds under the repo** (e.g. `samples/console-buyer` with its own settings.gradle.kts): they do NOT get build-logic conventions (spotless, Error Prone, `excludeTags("testnet")`) or the root version catalog unless wired explicitly; `make lint`/CI won't cover them.
- **Make targets gated on another task's files** (`test: x402-sample`, CI `-p <dir>` steps) break `make test`/CI on intermediate commits until that task lands; check the path exists.
- `make help` uses `%-14s`; target names longer than 14 chars misalign.
- Compose YAML merge (`<<: *anchor` inside a per-service `environment:`/`depends_on:`) replaces the whole key then re-merges the anchor: verified correct with Compose v5.5 `config --format json`. Compose reads `.env` only from the project dir (compose file dir), not CWD (verified).
- Env var names vs Spring relaxed binding: a compose var like `X402_FACILITATOR_URL` only works if application.yml references `${...}` or the name maps to the real property (`x402.server.facilitator.url` would need `X402_SERVER_FACILITATOR_URL`).

**Why:** these passed `docker compose config`, `bash -n` and `make -n` but would fail at acceptance or in CI.
**How to apply:** grep for these patterns first when reviewing infra/CI/trace changes; confirm library behaviour from the jar in ~/.gradle caches rather than from design docs.
