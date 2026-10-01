---
name: infra-redis-kafka-swap-audit
description: Valkey->Redis 8 / Redpanda->Apache Kafka 4.3 KRaft swap audit (2026-10-01) - no Crit/High/Med; JMX local connector, Redis 8 auto-loaded modules, Kafka admin-API suppression gap in THREAT_MODEL
metadata:
  type: project
---

Audit of the uncommitted `infra-redis-kafka` branch (ADR-0020), 2026-10-01. Result: no Critical/High/Medium.

Facts verified against the live stack (worth not re-deriving):
- Router guard rename was wording-only (no Lua/key/exception-type change); `git diff -M` on main sources.
- Kafka: controller listener bound to ::ffff:127.0.0.1:9093 inside the container; only 19092 published as 127.0.0.1:9092. `auto.create.topics.enable=true`, `delete.topic.enable=true`, no authorizer (Kafka defaults; Redpanda dev-container was equivalent). All 9 topics incl. `*.ledger-dlt` are declared via KafkaAdmin.NewTopics, so auto-create can be turned off safely.
- apache/kafka image starts the JDK *local* JMX connector (jmxremote=true, authenticate=false, no port) on an ephemeral wildcard port; a byte probe from another compose container got 0 bytes (LocalRMIServerSocketFactory closes non-local peers). Disable with a non-empty KAFKA_JMX_OPTS (launch script only defaults it when empty).
- Redpanda's unauthenticated Admin API (:9644) was never disabled before the swap; the swap removed it (improvement the docs don't mention).
- Redis 8 official image: protected-mode no, bind * (same as valkey image); entrypoint auto-loads search/json/bloom/timeseries (+vectorset built in); enable-module-command/debug-command no; runs as `redis` with NNP. Valkey plain image had no modules.
- libs/test-support uses saiman.java-library (no maven-publish); only saiman.published-library applies maven-publish -> no POM leak.

Open (pre-existing, not regressions): over the unauthenticated Kafka protocol a compose-network peer can delete topics/records, alter retention, commit offsets for group `ledger`, and (KIP-226, not exercised) rewrite per-broker advertised.listeners persisted in kafka_data. Reconciliation only checks payments the ledger already knows, so suppression blinds the audit; THREAT_MODEL M4 only describes forged writes. Revisit in M6 with broker auth/ACLs. See [[m4-close-audit]], [[threat-model-baseline]].
