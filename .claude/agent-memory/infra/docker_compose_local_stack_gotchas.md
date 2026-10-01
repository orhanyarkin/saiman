---
name: docker-compose-local-stack-gotchas
description: Environment quirks found building deploy/compose/docker-compose.yml for the M0 local stack (WSL2 + Docker Desktop)
metadata:
  type: project
---

Working notes from building `deploy/compose/docker-compose.yml` and
`deploy/compose/otel-collector.yaml` (M0 task T1, 2026-09-27).

**Port 6379 (Valkey) can be unavailable at the Docker daemon level even when nothing
inside the WSL distro is listening on it.** In this sandbox, `docker run -p
127.0.0.1:6379:6379 ...` failed with `ports are not available: exposing port TCP
127.0.0.1:6379 -> 127.0.0.1:0: /forwards/expose returned unexpected status: 500`, even
though `ss -ltnp` showed nothing on 6379 inside the distro and `docker ps` showed no
other container publishing it. This is Docker Desktop's WSL2 port-forwarding layer
(`/forwards/expose` is its internal API, visible via `docker context ls` showing a
`desktop-linux` context) — something on the Windows host side (or another WSL distro)
already holds that port, outside this agent's visibility/control (no sudo, no Windows
access). Confirmed the image and healthcheck (`valkey-cli ping`) both work correctly on
an alternate host port (`16380`), so this is purely a host port reservation, not a
config bug.

**Why:** cost ~15 minutes of debugging before concluding it was environmental. Don't
re-diagnose this from scratch next time — check `docker context ls` for a
`desktop-linux` entry and try a throwaway `docker run -p 127.0.0.1:<port>:<port>
<image>` on a different host port first to isolate "my compose file is wrong" from
"this exact host port is unavailable in this sandbox".

**Confirmed persistent, not project/container-specific (2026-09-28):** during a T1 fix
round, tore down the whole compose project (`docker compose down`, all containers
removed) and brought it back up under a *different* compose project name (`name:
saiman` added to the compose file, so containers became `saiman-*` instead of
`compose-*`, on a fresh network/fresh container IDs). Valkey on 127.0.0.1:6379 still
failed with the identical `/forwards/expose ... 500` error. This rules out "stale
forward left over from a specific container" as the cause — it's a real, currently
persistent Windows-host-side (or Docker Desktop) reservation of port 6379 in this
sandbox, not something a compose-level restart can clear. Same otel-collector
(4317/4318) port issue seen once mid-session (all requests got `curl: (56) Recv
failure: Connection reset by peer` even for a plain GET) *did* clear on a full
down+up — so 4317/4318 flakiness is transient/forward-staleness, but 6379 is not; treat
them differently when debugging.

**How to apply:** if `make infra-up` / `docker compose up` fails on a specific
`127.0.0.1:<port>` bind with `/forwards/expose ... 500`, it is very likely this sandbox
port-reservation issue, not a compose/service bug. Report it, don't silently change the
project's documented ports (deploy/compose intentionally uses the *real* Postgres/
Redpanda/Valkey default ports so `.env.example` and app config stay boring/standard).

**Redpanda `--mode dev-container` is real** (confirmed via Redpanda's own quick-start
docs), and needs two listeners to be reachable both from other containers (via the
compose network) and from the host: an `internal://` listener advertised as
`redpanda:9092` and an `external://` listener advertised as `localhost:9092`. A single
listener can't satisfy both audiences because the broker's advertised address is baked
into the Kafka protocol's metadata response. Current setup (after a reviewer fix
round): the external listener runs on *container* port 19092 but is published to the
host as `127.0.0.1:9092:19092` (not `19092:19092`), so both host tools and the broker's
own advertised address agree on the string "localhost:9092" — only one host port is
published for Kafka, not two.

**Jaeger v2 (`jaegertracing/jaeger:2.21.0`) needs zero config file** — the all-in-one
binary defaults to in-memory storage with OTLP receivers on 4317/4318 and the UI on
16686 out of the box. Only publish 16686 to the host; leave 4317/4318 unpublished on
the Jaeger container so they don't clash with the collector's own host-mapped
4317/4318 (both containers can use the same *internal* port numbers without conflict).

**Paketo "tiny" buildpack images have no shell**, so Compose can't `healthcheck` app
containers directly (confirmed in ADR-0007). Poll `/actuator/health` from the host
instead — see `scripts/wait-for-health.sh`.

See also [[otel-collector-0161-component-names]].

**2026-10-01 (ADR-0020): Valkey → Redis, Redpanda → Apache Kafka KRaft.** The Valkey port notes above apply to the
`redis` service unchanged (host port `REDIS_HOST_PORT`, default 16380). The Redpanda notes are history: compose now
runs `apache/kafka` with KAFKA_* env vars (fixed `CLUSTER_ID`, listeners INTERNAL kafka:9092 / EXTERNAL
localhost:9092 ← container 19092 / CONTROLLER localhost:9093), healthcheck via `/opt/kafka/bin/kafka-topics.sh`.
