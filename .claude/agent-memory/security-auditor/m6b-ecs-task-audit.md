---
name: m6b-ecs-task-audit
description: demo-lite ECS task audit (6eedd77, 2026-10-06) - shipped split correct; tftest misses master pw/openai placement, injection variants, pid_mode; shared netns makes Redis/ingest gaps live
metadata:
  type: project
---
Per-task audit of demo-lite ecs.tf/containers.tf/tests + aws/assets (A3a), 2026-10-06. No Crit/High. Shipped task def matches compose split (no MKK on ingest, no evals digest, corpus-restore is a 2nd ingest_owner holder).

Mediums:
- Guardrail: mutation run in scratch (terraform test, mock provider) PASSED with pg_master_password on seller-api AND kafka, openai_api_key on kafka, ledger token on seller-api, SPRING__CONFIG_IMPORT / SPRING_AUTOCONFIGURE_EXCLUDE_0 / BPL_DEBUG_ENABLED / BPL_JMX_ENABLED on apps, SPRING_FLYWAY__URL on migrators (env count 4 kept), pid_mode="task". SAIMAN_SECRETS_DIR caught only by accident ("SECRET" in name).
- Shared netns: THREAT_MODEL known gaps (Redis no auth = nonce store + router day cap; ingest /internal no auth incl. admin/retry-dlq) say "revisit before a deployment sharing the network"; the single task is that deployment, with 7 third-party images on loopback. ADR-0028 names only the task-role side.

Lows: assets bundle (bootstrap-roles.sh run as RDS master) + corpus dump unverified at runtime (no CI role has Put on demo/* yet; demo-up will need it); corpus_object_key regex allows shell metachars into `sh -c`; ECS Exec logging DEFAULT but task role has no logs:* (CloudTrail only), port-forward reaches every loopback port; X402 plaintext host 127.0.0.1 = every loopback port; SELLER_INTERNAL_ALLOWED_HOSTS port-less 127.0.0.1; Docker Hub kafka/otel tag pins (image test regex rejects @sha256); grafana endpoint unvalidated; sslmode=require no cert check.
Verified fine: SSM ARN build, no secrets in env/state, execution creds not in containers, no pidMode, SG no ingress, Kafka/Redis/otel bound 127.0.0.1, actuator health/info only, env beats configtree.
**Why:** follow-up audits (demo-up workflow, fixes) should check these closed.
**How to apply:** on fix review re-run the same mutations; on demo-up audit check S3 Put scope on demo/assets, manifest digest check, exec logging. Related: [[m6b-compose-migrate-audit]], [[m6b-tf-bootstrap-audit]].
