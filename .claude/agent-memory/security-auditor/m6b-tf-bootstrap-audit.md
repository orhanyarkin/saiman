---
name: m6b-tf-bootstrap-audit
description: M6b AWS bootstrap Terraform audit (2026-10-06) - apply role can mint/retarget demo-role trust, env-only OIDC sub unbound to ref, PR-readable demo-lite state
metadata:
  type: project
---

Audit of deploy/terraform/aws/bootstrap + .github/workflows/terraform.yml (commit 2d369a4, ADR-0028). No Crit/High.

Mediums (open until demo-lite / first human apply):
- Apply role has iam:CreateRole (boundary-conditioned only) + UpdateAssumeRolePolicy on role/saiman/demo/*: trust doc unconstrained, so a compromised apply run plants an out-of-state role trusted by an external account or repo:* OIDC; boundary still allows ssm:Get /saiman/demo/* + kms via ssm + s3 Put artifacts/*. Fix proposed: fixed demo roles in bootstrap, apply keeps only PassRole; check-demo-down.sh (not yet written) must list /saiman/demo/ roles.
- Trust subs environment:demo-apply / demo-destroy not bound to ref/workflow; README gives demo-destroy no protection and no deployment-branch policy -> any pushed branch gets destroy role. Fix: branch policy main + custom OIDC sub with job_workflow_ref (changes plan sub too).
- Plan role (sub pull_request) reads demo-lite state + ecs:DescribeTaskDefinition: state must never hold secrets (wo/ephemeral only; secrets.valueFrom not environment).

Lows: EC2 Resource * (tag conditions exist), destroy ecs:UpdateService can scale up, plan can delete .tflock, DeleteObject on whole demo-lite/*, shared boundary ceiling (single-task -> seller-api can read buyer key, ADR-0009 weakened on ECS).
Mitigated: exact sub/aud StringEquals, fork PRs get no OIDC, boundary required at CreateRole, boundary policy uneditable, bootstrap/ state unreachable by CI roles, bucket PAB/TLS/versioning/prevent_destroy, workflow contents:read no id-token, SHAs verified.

**Why:** next audit of demo-lite / demo-up workflows should check these were closed.
**How to apply:** when demo-lite module or plan/apply workflows land, verify state has no secrets, env branch policies, role creation model, check-demo-down.sh role enumeration. Related: [[m6b-db-migrate-audit]].
