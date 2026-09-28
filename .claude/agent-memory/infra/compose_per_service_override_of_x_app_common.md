---
name: compose-per-service-override-of-x-app-common
description: YAML merge-key pattern to add per-service environment/depends_on entries on top of deploy/compose's x-app-common anchor without losing the shared ones
metadata:
  type: project
---

`deploy/compose/docker-compose.yml`'s `x-app-common: &app-common` block is consumed by
every app service via `<<: *app-common`. That merge key covers the whole service
mapping (profiles, mem_limit, pull_policy, restart, environment, depends_on), so a
service that needs one extra env var or one extra `depends_on` entry can't just add a
top-level key — it has to fully replace `environment:`/`depends_on:` under `<<: *app-common`,
which would silently drop the shared ones, unless the *nested* maps are separately
anchored and merged too.

Fix used for seller-api needing `X402_SELLER_PAYTO_ADDRESS`/`X402_FACILITATOR_URL` and a
`depends_on: valkey: condition: service_healthy` (M1 T5, 2026-09-28): give the nested
maps their own anchors in `x-app-common` —
`environment: &app-common-env { ... }` (already existed) and
`depends_on: &app-common-depends-on { otel-collector: { condition: service_started } }`
(added) — then in the service block, override `environment:`/`depends_on:` with a
*second* merge key that pulls in the anchor plus the new keys:
```yaml
seller-api:
  <<: *app-common
  environment:
    <<: *app-common-env
    X402_SELLER_PAYTO_ADDRESS: ${X402_SELLER_PAYTO_ADDRESS:-}
  depends_on:
    <<: *app-common-depends-on
    valkey:
      condition: service_healthy
```
Confirmed with `docker compose --profile apps config --format json` that the resulting
`environment`/`depends_on` maps contain both the common and the per-service keys.

**Why:** avoids duplicating the whole shared environment block per service (which would
drift) while still letting one service opt into extra config; keeps
`docker compose config` valid with or without the new var set (use `${VAR:-}` so
`infra-up`/compose-policy config resolution never fails on an unset var — the app itself
enforces "required" by failing closed at startup, see ADR-0009).

**How to apply:** reuse this exact two-level-anchor pattern for any future service that
needs to extend (not replace) `x-app-common`'s `environment` or `depends_on`, e.g. when
the orchestrator gets the buyer-key `secrets:` mount in M3.
