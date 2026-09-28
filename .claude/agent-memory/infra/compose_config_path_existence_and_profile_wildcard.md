---
name: compose-config-path-existence-and-profile-wildcard
description: docker compose config's file-existence requirements differ by field (env_file vs secrets:file vs bind-mount source), and --profile '*' scans every service regardless of profile
metadata:
  type: project
---

Learned while building `scripts/test-check-compose-policy.sh` fixtures (M1 T5 review
round 1, 2026-09-28), tested against Docker Compose v5.5.1:

- `docker compose ... config` **requires** an `env_file:` source to exist on disk (fails
  with `env file ... not found`), even with `--no-path-resolution`.
- It does **not** require a `secrets:`/`configs:` top-level entry's `file:` source to
  exist, nor a bind-mounted `volumes:` entry's `source:` to exist — both resolve to an
  absolute path in the JSON output with no existence check. `docker compose up` would
  presumably fail later on a missing secret/bind source, but `config` (what
  `check-compose-policy.sh` uses) does not care.
- Plain `docker compose config` (no `--profile` flag at all) only includes services with
  **no** `profiles:` key — a service under `x-app-common`'s `profiles: ["apps"]` merge
  key (or any other explicit profile) is invisible without naming its profile. `--profile
  '*'` (quoted, to stop the shell from globbing it) includes every service regardless of
  profile — used in `scripts/check-compose-policy.sh` so a leaked key on a
  non-`"apps"`-profile service (e.g. a one-off debug service) can't hide from the policy
  scan just by sitting behind an unused profile name.

**Why:** cost real debugging time building fail/pass fixtures for
`scripts/testdata/compose-policy/`: a fixture using `env_file` needed a real (harmless,
non-`.env`-named) backing file, while `secrets:`/`configs:`/bind-mount fixtures could
reference nonexistent paths and still resolve cleanly — mixing these two assumptions up
silently breaks fixtures with a `config` error unrelated to the policy being tested.

**How to apply:** when writing more `docker compose config`-based fixtures or checks,
remember env_file is the one field that needs a real file; everything else in this list
doesn't. When a check needs to see services under a profile other than `"apps"`, use
`--profile '*'`, not a growing list of `--profile <name>` flags.

See also [[docker_compose_local_stack_gotchas]], [[compose_per_service_override_of_x_app_common]].
