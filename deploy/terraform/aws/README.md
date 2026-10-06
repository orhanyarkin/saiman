# Terraform on AWS

Region `eu-central-1` only. Design: ADR-0028 (demo-lite), ADR-0004 (hybrid deployment).

```
bootstrap/   state bucket + GitHub OIDC provider + roles saiman-gha-plan/apply/destroy + saiman-demo-boundary
modules/     demo-lite (added later)
```

Nothing here is applied by an agent or by CI `plan`. Real `apply`/`destroy` run only in GitHub Actions
(OIDC, human-triggered) once bootstrap exists. Bootstrap itself is applied once by the human.

## One-time bootstrap (human)

Prerequisites: an AWS login via IAM Identity Center (AdministratorAccess, MFA); Terraform >= 1.11.
The repository stays free of state, `.tfvars` and `backend.hcl` (all gitignored).

1. Pick a globally unique bucket name, for example `saiman-<account-id>-tfstate`. Create
   `deploy/terraform/aws/bootstrap/terraform.tfvars` (gitignored):
   ```hcl
   state_bucket_name = "saiman-123456789012-tfstate"
   ```
2. Log in (`aws sso login --profile <profile>`; export `AWS_PROFILE`), then:
   ```bash
   cd deploy/terraform/aws/bootstrap
   terraform init
   terraform plan
   terraform apply
   ```
   Check the plan: one bucket (+ config), one OIDC provider, three roles, one managed policy.
   If the account already has a `token.actions.githubusercontent.com` OIDC provider (only one is
   allowed per account), `terraform import aws_iam_openid_connect_provider.github <arn>` first.
3. Move the bootstrap state into the bucket it created:
   ```bash
   cp backend.tf.example backend.tf
   printf 'bucket = "%s"\n' "saiman-123456789012-tfstate" > backend.hcl   # gitignored
   terraform init -migrate-state -backend-config=backend.hcl
   ```
   Answer `yes` to copy the state, then delete the local `terraform.tfstate*`. Locking uses S3
   natively (`use_lockfile = true`), there is no DynamoDB table. State keys:
   `bootstrap/terraform.tfstate` (this stack) and `demo-lite/terraform.tfstate` (demo-lite).
   Do not commit `backend.tf` until you are happy with the layout; `backend.hcl` is never committed.
4. In GitHub: Settings > Environments, create `demo-apply` (required reviewer: you) and
   `demo-destroy`. Add repository variables with the role ARNs and the bucket name from
   `terraform output` (`PLAN_ROLE_ARN`, `APPLY_ROLE_ARN`, `DESTROY_ROLE_ARN`, `TF_STATE_BUCKET`).
5. Never `terraform destroy` bootstrap: the bucket has `prevent_destroy`, and the state it holds
   is versioned (noncurrent versions expire after 30 days).

## Roles

| Role | Trust (`sub`) | Can |
| --- | --- | --- |
| `saiman-gha-plan` | `repo:orhanyarkin/saiman:pull_request`, `repo:orhanyarkin/saiman:ref:refs/heads/main` | read/describe, read demo-lite state, hold the lock. Explicit Deny: `ssm:GetParameter*` on `/saiman/*`, `s3:GetObject` on `artifacts/*` and `demo/*` (PR code runs during plan) |
| `saiman-gha-apply` | `repo:orhanyarkin/saiman:environment:demo-apply` | create/update/delete `saiman-demo*` resources; `iam:CreateRole` only under `/saiman/demo/` and only with boundary `saiman-demo-boundary` |
| `saiman-gha-destroy` | `repo:orhanyarkin/saiman:environment:demo-destroy` | delete, stop, scale to zero, deregister task definitions; cannot create anything |

All three are locked to `eu-central-1` (`aws:RequestedRegion`; IAM, STS and Budgets are global and exempt).

## Offline checks (no credentials)

```bash
cd deploy/terraform/aws/bootstrap
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test                       # mock_provider assertions on trust, denies, boundary, bucket
tests/check-prevent-destroy.sh       # lifecycle is invisible to terraform test
```
`.github/workflows/terraform.yml` runs the same on pull requests and on `main`.
