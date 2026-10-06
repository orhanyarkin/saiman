# ADR-0028: demo-lite on AWS

Status: Accepted (2026-10-06, human decision). Amends ADR-0004 (deployment), ADR-0007 (image publishing) and ADR-0009 (secrets) for the AWS demo only, and relaxes CLAUDE.md rule 7 for TTL expiry.

## Context
The demo runs on AWS for a few hours, is captured as JSON for the static replay, and is destroyed. Cost and attack surface must stay minimal.

## Decision
- **One Fargate ARM task** (2 vCPU / 8 GB) with every container on localhost, including Kafka and Redis, plus a private RDS `db.t4g.micro`. Service Connect (separate tasks) is the rejected option and becomes the `enterprise` story.
- **No load balancer.** The human reaches the stack through an SSM port-forward to the `web` container; nothing is exposed on the internet. The public demo is the recorded replay.
- **Secrets** are SSM SecureString parameters injected as ECS environment variables; Terraform never reads them. The only secret Terraform sees is the RDS master password, as an ephemeral write-only argument.
- **Images**: public GHCR packages, `sha-<12>` tags, amd64 and arm64 pushed by native runners from `main` and joined by a manifest.
- **Corpus**: restored from a private dump in the state bucket (public KAP data, private storage), not rebuilt from MKK (5 requests/min).
- **TTL**: `demo-up` sets an expiry. A `demo-reaper` workflow (cron, 30 min) and EventBridge Scheduler (ECS desired count 0, RDS stop) tear down or stop an expired demo. `demo-up` and `demo-down` are triggered only by the human. This relaxes rule 7 for expiry only; the destroy role cannot create resources.
- **Bootstrap** (human applies once): state bucket (versioned, SSE-S3, TLS-only, `prevent_destroy`), GitHub OIDC provider, three roles (`plan`, `apply`, `destroy`) with a permissions boundary. CI runs `fmt`, `validate`, `test` and `plan` only, never `apply`.
- **Teardown check**: `check-demo-down.sh` fails if anything but the bootstrap stack remains.

## Consequences
+ About $0.55-0.80 per 4-hour session; about $0.003/month idle.
− Only the human sees the live stack. RDS master is not a superuser, so the role bootstrap needs a non-superuser path, proven first by the human's `demo-up`.
− GitHub cron can be delayed; the Scheduler backstop covers compute, RDS storage remains until destroy.
