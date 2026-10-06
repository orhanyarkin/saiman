---
name: m6b-close-audit
description: M6b (migrate one-shots + AWS demo-lite + lifecycle workflows) milestone-end audit 2026-10-06 @ 990ba58 - no Crit/High; GHCR tag poisoning bypasses image ancestry check, CI guards path-filtered away from the workflows they guard
metadata:
  type: project
---
Read-only cross-cutting audit 2026-10-06, branch m6b-deploy-migrate @ 990ba58 (51 commits over main). No probes against AWS (nothing applied yet).

No Critical/High. Findings:
- Medium: demo-up resolves image digests from mutable GHCR `sha-<12>` tags; any workflow on ANY branch can request `packages: write` and re-push that tag (also the images job runs the whole Gradle build + unpinned Paketo builder + main Gradle cache with GITHUB_TOKEN in env). Ancestry check proves the commit, not the image; THREAT_MODEL "Image integrity" overclaims. Fix: provenance (attest-build-provenance in a build-free job + `gh attestation verify --signer-workflow ci.yml --source-ref refs/heads/main`) or compare with digests artifact of the main push run.
- Medium: terraform.yml paths filter (deploy/terraform/**, terraform.yml) means check-workflow-run-blocks.sh, test-ops-scripts, check-ssm-names never run when demo-*.yml, ci.yml, scripts/ensure-secret-files.sh, with-auth-digests.sh or deploy/compose/postgres/** change; default file list excludes ci.yml; only id-token jobs count as privileged.
- Low: router day cap + nonce store live only in Redis, ECS Redis has no persistence (compose has a volume) -> cap resets on Redis restart; demo-down/destroy dispatchable by any writer/agent (Claude settings lack gh workflow run / ssm get-parameter / ecs execute-command rules); env protections unverified at run time; mirror images tag-pinned + no Dependabot terraform ecosystem, tftest hardcodes tags (silent drift); infracost key reachable by same-repo PR code; task role reads whole artifacts/* and demo/*; stale docs (THREAT_MODEL:320 "not yet on ECS", :342 vs :347 GHCR pin, aws README "three roles" vs six, README CI/infracost lines).
Verified held: fixed demo roles + PassRole-only apply, plan Denies, ECS exact secret/env maps + injection + pid/ipc tftest, DbMigrate sentinel + verifySession, web build split + dist digest, expiry tag + age cap + delete-last, secrets file:// + masks + plan/state scan, ephemeral password_wo, exec logging NONE, actuator health/info only, no committed secrets, gitignore covers tfstate/tfvars/backend.hcl/secrets.
**Why:** first `terraform apply` of bootstrap and first `demo-up` are human steps right after this audit.
**How to apply:** on the fix review check provenance verification exists in demo-up and the guard workflow triggers on .github/workflows/**; before any second deployment re-check Redis persistence/cap seeding. Related: [[m5-close-audit]], [[m4b-close-audit]].
