---
name: m6b-demo-lifecycle-audit
description: M6b demo-up/down/destroy/reaper + ops scripts audit (e2162f9, 2026-10-06) - no Crit/High; web build in OIDC apply job, tag-pinned app images, no TTL ceiling, reaper disarmed on failed teardown check
metadata:
  type: project
---
Per-task audit of .github/workflows/demo-*.yml, terraform.yml plan/infracost, deploy/terraform/aws/ops/**, Makefile demo targets (HEAD e2162f9, which added apply s3:PutObject demo/* + ssm Put/AddTags). No Crit/High.

Medium:
- demo-up runs `pnpm install` (setup-node pnpm cache) + `pnpm build` (via build-assets.sh) in the SAME job as id-token:write, after prepare-demo-secrets wrote $RUNNER_TEMP/secrets. Compromised npm build dep -> mints demo-apply OIDC token (ACTIONS_ID_TOKEN_REQUEST_*) -> apply role, reads buyer/OpenAI/DB secrets. Fix: build web dist in a separate job without id-token/secrets, pass artifact + digest; no cache in privileged job.

Lows: app images pinned by mutable GHCR tag, image_sha not checked as ancestor of main, no digest/provenance; expires-at tag fully trusted (no ceiling: cap by DescribeParameters LastModifiedDate+8h30m); demo-destroy deletes expires-at before check-demo-down (leftovers disarm reaper), no failure summary, stale .tflock after killed apply blocks reaper; check-demo-down misses /ecs/saiman-demo* log groups and non-saiman-demo task families; demo-up re-run on live demo rotates SSM pw but RDS master fixed (password_wo_version=1); public plan artifact/logs show live state; scan-tf-state case-sensitive/0x forms.
Verify at first run: S3 backend init may need ListBucket for workspace prefix `env:/` (stmt_state_list is demo-lite/* only); RDS aws/rds key without kms grants; session-manager-plugin binds localhost.
Verified fine: all 7 action SHAs match tags; no ${{}} in run; inputs regex-validated; GITHUB_ENV/OUTPUT values validated; destroy role cannot write tags; only_if_expired re-check under shared demo-lite lock; secrets via file:// and masked before use; ephemeral master pw; plan+state scanned; provider lock file committed; force_delete lets destroy work without UpdateService.
**Why:** next fix review / milestone audit should check these.
**How to apply:** on re-review confirm separate build job + digest-pinned images; check reaper behaviour after a failed teardown. Related: [[m6b-tf-bootstrap-audit]], [[m6b-ecs-task-audit]].
