# ADR-0028: demo-lite on AWS

Status: Accepted (2026-10-06, human decision); decision 1 amended 2026-10-18 (two task definitions, human decision). Amends ADR-0004 (deployment), ADR-0007 (image publishing) and ADR-0009 (secrets) for the AWS demo only, and relaxes CLAUDE.md rule 7 for TTL expiry.

## Context
The demo runs on AWS for a few hours, is captured as JSON for the static replay, and is destroyed. Cost and attack surface must stay minimal.

## Decision
- **One long-running Fargate ARM task** (2 vCPU / 8 GB, the `stack`: Kafka, Redis, the four services, nginx, the collector, `assets` and `readiness`, ten containers) with every container on localhost, **plus one run-to-completion `init` task** (1 vCPU / 4 GB: `assets`, `db-init`, the four `*-migrate`, `corpus-restore` and an essential `done` container), on a private RDS `db.t4g.micro`. The split is forced by ECS: at most **10 containers per task definition**. The first `demo-up` failed at `RegisterTaskDefinition` ("Too many containers") with 16. Folding the init containers together is not possible without giving up ADR-0027 (the migrators are the service images), and dropping the collector or the readiness gate would still leave 14. `demo-up` creates the service at zero tasks, runs the init task with `ecs:RunTask` (`ops/run-init-task.sh`, every container must exit 0, a stuck task is stopped), and only then sets the desired count to 1; `dependsOn` cannot cross task definitions, so that sequence replaces the `<svc>-migrate` dependency. Service Connect (separate long-running tasks) is the rejected option and becomes the `enterprise` story.
- **No load balancer.** The human reaches the stack through an SSM port-forward (by convention to the `web` container; a port-forward reaches every loopback port, so this is not a control); nothing is exposed on the internet. The public demo is the recorded replay.
- **Secrets** are SSM SecureString parameters injected as ECS environment variables; Terraform never reads them. The only secret Terraform sees is the RDS master password, as an ephemeral write-only argument.
- **Images**: public GHCR packages, `sha-<12>` tags, amd64 and arm64 pushed by native runners from `main` and joined by a manifest.
- **Corpus**: restored from a private dump in the state bucket (public KAP data, private storage), not rebuilt from MKK (5 requests/min).
- **TTL**: `demo-up` sets an expiry. A `demo-reaper` workflow (cron, 30 min) and EventBridge Scheduler (ECS desired count 0, RDS stop) tear down or stop an expired demo. `demo-up` and `demo-down` are triggered only by the human. This relaxes rule 7 for expiry only; the destroy role cannot create resources.
- **Bootstrap** (human applies once): state bucket (versioned, SSE-S3, TLS-only, `prevent_destroy`), GitHub OIDC provider, three roles (`plan`, `apply`, `destroy`) with a permissions boundary. CI runs `fmt`, `validate`, `test` and `plan` only, never `apply`.
- **Teardown check**: `check-demo-down.sh` fails if anything but the bootstrap stack remains.

## Consequences
- The apply role's sessions may last two hours (`max_session_duration` 7200 s, `demo-up` asks for it) because one `demo-up` holds a session through apply, the init task and the readiness wait; the plan and destroy roles stay at one hour, so a leaked apply credential is valid for up to two hours instead of one, behind the same `demo-apply` environment gate and the 75-minute job timeout.
- The master and owner passwords now exist only in the init task definition; the long-running task never sees them (a security gain over the single task). The apply role gains `ecs:RunTask`, limited to `saiman-demo*` task definitions and the `saiman-demo*` cluster (condition `ecs:cluster`), and each fixed role may be passed to one service only (task and execution roles to `ecs-tasks`, the scheduler role to `scheduler`). `demo-destroy` stops a leftover init task before deleting the cluster, because Terraform does not know it.
- The long-running task still shares loopback: Redis is password-protected (`redis_password`), but ingest `/internal/**` stays unauthenticated by design until `libs/api-security` covers ingest (documented follow-up), so any container of the task can reach it. Runtime assets and the corpus dump are verified against digests passed to Terraform (`assets_manifest_sha256`, `corpus_sha256`).
+ About $0.55-0.80 per 4-hour session; about $0.003/month idle.
− Only the human sees the live stack. RDS master is not a superuser, so the role bootstrap needs a non-superuser path, proven first by the human's `demo-up`.
− GitHub cron can be delayed; the Scheduler backstop covers compute, RDS storage remains until destroy.
