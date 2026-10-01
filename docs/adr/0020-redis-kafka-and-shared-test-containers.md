# ADR-0020: Redis and Apache Kafka instead of Valkey and Redpanda; one test container set per JVM

Status: Accepted (2026-10-01).

## Context
M0 picked Valkey (cache and counters) and Redpanda (Kafka API) for their small footprint and licences. Both work, but this is a portfolio project: a reviewer or employer should recognise the stack at a glance and be able to map it onto what they run. Redis and Apache Kafka are the tools most teams know and operate; Valkey and Redpanda need a sentence of explanation each, and Redpanda adds its own CLI (`rpk`), config file and HTTP services to reason about.

Separately, the M4 test suite started about 30 Postgres, 5 Redpanda and 3 Valkey containers in a parallel `check` (docs/PROGRESS.md, "Known flaky"), because every test configuration declared its containers as Spring beans and each distinct cached context started its own set.

## Decision
**Stack preference.** Prefer widely known tools (Redis, Kafka and the like). Choosing a less common alternative needs an ADR with the reason and the human's approval (CLAUDE.md).

**Redis** (`redis:8.10.2-alpine`) replaces Valkey everywhere: compose service `redis`, host port `REDIS_HOST_PORT` (default 16380, as before), the router's guards renamed `RedisCostGuard` / `RedisScopedCostGuard`. Behaviour and keys are unchanged: the code only uses commands both servers share (`SET NX PX`, `INCRBY`, hashes, Lua via `EVAL`).

**Apache Kafka** (`apache/kafka:4.3.1`) replaces Redpanda: one node in KRaft mode (broker and controller in one process, no ZooKeeper), a fixed `CLUSTER_ID` so the persisted log directory survives restarts, internal-topic replication factor 1. The listener layout stays as before: `kafka:9092` inside the compose network, `localhost:9092` on the host (container port 19092), the controller listener only inside the container. Nothing in the code changes: the services always used the Kafka protocol through Spring Kafka.

**Images are pinned to exact tags** in compose and in tests, and both use the same tags.

**Tests: one Postgres, one Kafka and one Redis container per test JVM** (`libs/test-support`, test-only, not published):
- `SharedContainers` starts each container on first use and never stops it; Testcontainers' reaper removes it when the JVM exits. Gradle runs one test JVM per module, so a full parallel `check` runs at most one set per module instead of one per cached context.
- Spring tests import `PostgresContainerConfiguration`, `KafkaContainerConfiguration` or `RedisContainerConfiguration`. Kafka and Redis are beans with `@ServiceConnection` and `destroyMethod = ""` (closing one cached context must not stop a container the others use).
- **Postgres is shared as a server, not as a database:** each application context gets a fresh database (`CREATE DATABASE test_<n>`) through a `JdbcConnectionDetails` bean, so contexts stay as isolated as they were with one container each (their own Flyway run, their own outbox table). `max_connections` is raised to 1000 because every cached context keeps its pool open.
- Kafka and Redis are shared as they are. Spring Framework 7 pauses inactive cached contexts (their listener containers and task schedulers stop) and test classes run one at a time. Application listeners keep their fixed consumer group (e.g. the ledger's `ledger`): a paused context leaves the group, and a resumed one continues from the group's committed offset, so it never re-reads records another context consumed into its own database. Test-side consumers use random group ids; Redis tests use unique keys or days. Tests that pause a container (`docker pause`) pause the shared one and resume it in `finally`/`@AfterEach`.
- No `withReuse(true)`: it needs a per-machine `~/.testcontainers.properties` and survives across runs, which makes failures depend on the developer's machine.

## Licences
- **Redis**: 7.2 and earlier are BSD-3-Clause; 7.4 moved to RSALv2 or SSPLv1; **8.0 and later are tri-licensed RSALv2 / SSPLv1 / AGPLv3** (redis.io/legal/licenses, checked 2026-10-01). Valkey is the BSD-3 fork of Redis 7.2.4 under the Linux Foundation.
- **Apache Kafka**: Apache License 2.0. Redpanda's core is under the Business Source License 1.1 (source-available, converting to Apache 2.0 after four years).
- None of these restrict running the unmodified server for local development, tests or our own demo deployment: the restrictions in RSALv2 and SSPLv1 target offering the database itself as a managed service, and AGPLv3's network clause applies to modified versions offered to users over a network. We neither modify nor offer Redis or Kafka as a service. If that ever changes, Valkey stays a drop-in replacement (same protocol, same client).

## Consequences
+ The stack is the one most readers already know; the compose file needs no Redpanda-specific workarounds (the pandaproxy and schema-registry `sed` hack is gone: plain Kafka has neither service).
+ A full parallel `check` starts a handful of containers instead of about 40; test start-up is faster and the "connection refused" flake loses its most likely cause.
− Kafka on the JVM uses more memory than Redpanda (heap capped at 512 MB in compose) and starts more slowly (about 10-20 s to healthy).
− Redis 8 is not under an OSI licence unless AGPLv3 is chosen; acceptable for the reasons above, and recorded here so the choice is deliberate.
− Shared Kafka and Redis mean tests must not depend on an empty broker or keyspace; the Postgres isolation per context keeps database assertions unchanged.
− Existing compose volumes (`redpanda_data`, `valkey_data`) are not migrated: the new services start empty. Kafka holds no state of record (the outboxes and ledgers are in Postgres) and Redis only holds counters and caches with TTLs. Remove the old volumes by hand: `docker volume rm saiman_redpanda_data saiman_valkey_data`.

## Supersedes
The Valkey and Redpanda choices in ADR-0001, ADR-0003, ADR-0004, ADR-0008, ADR-0011, ADR-0013, ADR-0016 and ADR-0018 (each carries a short amendment pointing here).
