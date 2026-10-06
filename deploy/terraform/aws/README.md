# Terraform on AWS

Region `eu-central-1` only. Design: ADR-0028 (demo-lite), ADR-0004 (hybrid deployment).

```
bootstrap/   state bucket + GitHub OIDC provider + roles saiman-gha-plan/apply/destroy + saiman-demo-boundary + the three fixed demo roles
demo-lite/   the demo stack (ECS, RDS, network, scheduler); references the fixed roles by ARN
assets/      build-assets.sh stages web dist, nginx/otel config and DB scripts for the task's S3 sync (make test-aws-assets)
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
4. In GitHub, create two environments and lock them down (Settings > Environments):
   - `demo-apply`: required reviewer = you; **Deployment branches: Selected branches = `main` only**;
     "Allow administrators to bypass" **off**.
   - `demo-destroy`: **Deployment branches: Selected branches = `main` only**; admin bypass **off**.
   - Branch protection on `main` (PR required, no force-push). The OIDC `sub` claim only names the
     environment, so these settings are what stop a branch workflow from assuming the roles.
   Add repository variables with the role ARNs and the bucket name from `terraform output`
   (`PLAN_ROLE_ARN`, `APPLY_ROLE_ARN`, `DESTROY_ROLE_ARN`, `TF_STATE_BUCKET`).
   Stronger option (documented follow-up, not done): customise the OIDC `sub` claim template for the
   repository to include `job_workflow_ref` and trust only `.github/workflows/demo-up.yml@refs/heads/main`.
5. Never `terraform destroy` bootstrap: the bucket has `prevent_destroy`, and the state it holds
   is versioned (noncurrent versions expire after 30 days).

## Roles

| Role | Trust (`sub`) | Can |
| --- | --- | --- |
| `saiman-gha-plan` | `repo:orhanyarkin/saiman:pull_request`, `repo:orhanyarkin/saiman:ref:refs/heads/main` | read/describe, read the demo-lite state. No S3 write at all, so plan workflows must run `terraform plan -lock=false`. Explicit Deny: `ssm:GetParameter*` on `/saiman/*`, `s3:GetObject` on `artifacts/*` and `demo/*` (PR code runs during plan) |
| `saiman-gha-apply` | `repo:orhanyarkin/saiman:environment:demo-apply` | create/update/delete `saiman-demo*` resources. IAM: `iam:PassRole` of the three fixed demo roles (path `/saiman/demo/`, ecs-tasks and scheduler only) and nothing else: it cannot create, edit or re-trust a role. `s3:DeleteObject` only on the state lock object |
| `saiman-gha-destroy` | `repo:orhanyarkin/saiman:environment:demo-destroy` | delete, stop and modify existing `saiman-demo*` resources (including `rds:ModifyDBInstance`); no create/register/run/put action and no IAM write. Residual: it can still alter what exists (cost denial of service, not escalation). Teardown relies on `ecs delete-service --force` (so demo-lite's `aws_ecs_service` needs `force_delete = true`); scale-to-zero is the Scheduler role's job |

All three are locked to `eu-central-1` (`aws:RequestedRegion`; IAM, STS and Budgets are global and exempt),
and all three carry explicit Denies for secret values (`ssm:GetParameter*` on `/saiman/*`) and for reading
`artifacts/*` and `demo/*`.

The workload roles (`saiman-demo-task-execution`, `saiman-demo-task`, `saiman-demo-scheduler`, path
`/saiman/demo/`) are fixed in `bootstrap/demo_roles.tf`: service-principal trust plus `aws:SourceAccount`,
the `saiman-demo-boundary`, inline policies. Only the execution role may read `/saiman/demo/*` (ECS
`secrets[].valueFrom`); the task role has no SSM permissions and is read-only on `artifacts/`.

Known limits:
- The boundary bounds what the demo roles can do, not who can use them: a malicious approved apply can
  still deploy a workload that runs as them and reads `/saiman/demo/*` (inherent; those secrets are testnet
  keys and a capped OpenAI key only).
- EC2 write actions (VPC, subnets, security groups) are allowed on `*`, limited by the region lock only.
  Tag-based scoping (`aws:ResourceTag/project`, `aws:RequestTag/project` on Create via `ec2:CreateAction`)
  is a documented follow-up: it cannot be verified offline and makes destroy fragile for untagged
  leftovers. Recommended instead: run the demo in a dedicated AWS account.

## Offline checks (no credentials)

```bash
cd deploy/terraform/aws/bootstrap
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test                       # mock_provider assertions on trust, denies, fixed roles, boundary, bucket
tests/check-prevent-destroy.sh       # lifecycle is invisible to terraform test
```
The same four commands plus `tests/check-foundation.sh` run in `demo-lite/`.
`.github/workflows/terraform.yml` runs the same on pull requests and on `main`.

## demo-lite (foundation)

`demo-lite/` is a root module (state key `demo-lite/terraform.tfstate`). This part holds the network,
RDS, IAM, logs and the expiry backstop; the ECS cluster, service and containers are added next to it.
Everything is tagged `project=saiman`, `saiman:stack=demo-lite`, `saiman:session=<session_id>`.

| File | Contents |
| --- | --- |
| `network.tf` | VPC, two public subnets (no NAT, no ALB), internet gateway (no VPC endpoints); task SG with no ingress and egress 443 and 5432 to RDS only; RDS SG accepting 5432 from the task SG only |
| `rds.tf` | PostgreSQL 17, `db.t4g.micro`, 20 GB gp3, private, encrypted, no backups or final snapshot; parameter group pins `log_statement=none`, `log_min_error_statement=panic` (role-bootstrap passwords never reach RDS logs) and `rds.force_ssl=1` |
| `iam.tf` | task execution role (reads only SSM `/saiman/demo/*`, writes the log group), task role (S3 GetObject on `artifacts/*` and `demo/*`, ECS Exec), scheduler role; all under `/saiman/demo/` with the bootstrap boundary |
| `logs.tf` | log group `/saiman-demo-lite`, 1 day retention (the `saiman-demo` prefix is what the bootstrap apply role and boundary allow) |
| `scheduler.tf` | one-time EventBridge Scheduler schedules at `expires_at` + 30 minutes: ECS `desiredCount=0` and RDS stop (universal targets, no Lambda) |

Notes:
- SSM SecureString parameters under `/saiman/demo/` are created by the `demo-up` workflow, never by
  Terraform; the module only references their ARN pattern.
- The RDS master password is `var.db_master_password` (ephemeral, sensitive) passed as `password_wo`
  with `password_wo_version = 1`: it never lands in plan or state. Pass it as `TF_VAR_db_master_password`.
- `expires_at` must be UTC (`2026-10-06T18:00:00Z`). The backstop stops compute only; RDS storage and
  the VPC remain until `demo-down` destroys the stack, and AWS restarts a stopped RDS after 7 days.
- RDS `storage_encrypted` uses the default `aws/rds` key; it is assumed to work with the apply role (no KMS grants) and is the first thing to check on the first `demo-up`.
- Required variables: `image_tag`, `session_id`, `expires_at`, `x402_seller_payto_address`,
  `auth_digests`, `state_bucket_name` (see `variables.tf`).

Offline checks (no credentials):
```bash
cd deploy/terraform/aws/demo-lite
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
terraform test                 # mock_provider assertions
tests/check-foundation.sh      # default tags, single ingress rule, write-only password
```

## demo-lite (ECS task)

`ecs.tf` declares the cluster `saiman-demo`, the single Fargate ARM64 task definition (family
`saiman-demo-lite`, roles = the fixed bootstrap ARNs) and the service `saiman-demo-lite`
(`desired_count = 1`, public IP, `enable_execute_command`, `force_delete`, no steady-state wait).
`containers.tf` mirrors `deploy/compose/docker-compose.yml` for one task, all containers on localhost:

| Container | Kind | Notes |
| --- | --- | --- |
| `assets` | one-shot | `aws s3 sync` of `s3://<state bucket>/demo/assets/<session_id>/` into the `assets` volume (layout of `aws/assets/build-assets.sh`), copies `web/dist` and `nginx/default.conf` into `web-html` / `web-conf`, fetches the corpus dump + `.meta.json` (`corpus_object_key`) into `corpus` |
| `db-init` | one-shot | creates the `vector` extension, then `postgres/bootstrap-roles.sh` (`BOOTSTRAP_SECRET_SOURCE=env`, `BOOTSTRAP_RDS=1`); master password and `PW_*` via `secrets` only |
| `<svc>-migrate` x4 | one-shot | `SAIMAN_RUN_MODE=migrate`, owner user, `SPRING_FLYWAY_PASSWORD` via `secrets` only |
| `corpus-restore` | one-shot | `postgres/corpus-restore.sh /corpus` as `ingest_owner` (after `ingest-migrate`) |
| `kafka`, `redis` | essential | loopback only, health checks |
| `orchestrator`, `seller-api`, `ledger`, `ingest` | essential | app password only, `SPRING_FLYWAY_ENABLED=false`, 768 MiB hard limit |
| `otel-collector` | non-essential | `assets/otel/config.yaml`; `grafana.yaml` overlay only when `grafana_otlp_endpoint` is set |
| `web` | essential | nginx on port 80, the conventional SSM port-forward target (not a control: a forward reaches every loopback port) |
| `readiness` | one-shot | runs `/assets/scripts/readiness.sh` (the assets prefix must contain `scripts/readiness.sh`), exits 0 when all four services are UP |

SSM parameters the `demo-up` workflow must create under `/saiman/demo/` (SecureString):
`pg_master_password`, `pg_<schema>_{owner,app}_password` for `orchestrator`, `ledger`, `seller_api`, `ingest`,
`redis_password` (Redis `--requirepass`; also `SPRING_DATA_REDIS_PASSWORD` on the four apps), `x402_buyer_private_key`, `openai_api_key`, `seller_service_token_ledger`, and `grafana_otlp_auth`
(only when `grafana_otlp_endpoint` is set). Spring cannot read file secrets on ECS, so the secrets are env vars
with relaxed-binding names (`X402_CLIENT_PRIVATE_KEY`, `SAIMAN_ROUTER_OPENAI_API_KEY`,
`SAIMAN_LEDGER_SELLER_SERVICE_TOKEN`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_FLYWAY_PASSWORD`).

Integrity inputs: `assets_manifest_sha256` (the `manifest-sha256` line of `assets/build-assets.sh`) and `corpus_sha256` are required; the `assets` container verifies both before copying or restoring anything. Notes: `sslmode=require` encrypts but does not verify the RDS certificate (`verify-full` is a follow-up); `corpus-restore` is a second holder of `ingest_owner`; kafka and the collector are pinned by `@sha256:` index digest.

Extra offline check: `tests/check-task.sh` (no secret reads, no IAM, buyer key only on the orchestrator).

## Running a demo

Everything runs in GitHub Actions through OIDC; nothing here needs AWS credentials on your laptop except the
read/ops commands at the end (your own SSO profile).

Repository variables (Settings > Secrets and variables > Actions > Variables): `PLAN_ROLE_ARN`, `APPLY_ROLE_ARN`,
`DESTROY_ROLE_ARN`, `TF_STATE_BUCKET`, `CORPUS_SHA256` (sha256 of the corpus dump, from `make corpus-export`),
optional `CORPUS_OBJECT_KEY` (default `artifacts/corpus/latest.dump`), optional `GRAFANA_OTLP_ENDPOINT`.
`demo-apply` environment secrets: `X402_BUYER_PRIVATE_KEY` (throwaway testnet key), `OPENAI_API_KEY` (capped
key), `X402_SELLER_PAYTO_ADDRESS`, optional `GRAFANA_OTLP_AUTH`. Repository secret `INFRACOST_API_KEY` is optional.

| Workflow | Trigger | Does |
| --- | --- | --- |
| `demo-up` | you, `workflow_dispatch` on `main`; inputs `ttl_hours` 1..8 (default 4), `image_sha` | checks the five GHCR `sha-<12>` manifests for arm64+amd64, generates the DB passwords/tokens (`scripts/ensure-secret-files.sh`, masked), writes the SSM parameters and `/saiman/demo/expires-at`, builds and uploads the assets, `terraform plan` + secret scan + `apply`, waits for the `readiness` container, prints the tunnel command. A failure after `apply` destroys nothing: run `demo-down` or let the reaper do it |
| `demo-down` | you | calls `demo-destroy` |
| `demo-destroy` | called | `terraform destroy`, deletes `/saiman/demo/*`, the `demo/` S3 prefix (all versions) and task definitions, then `ops/check-demo-down.sh` |
| `demo-reaper` | cron every 30 min | destroys the demo only when `/saiman/demo/expires-at` is in the past (re-checked under the `demo-lite` lock). The only automation allowed to destroy (ADR-0028, rule 7) |

SSM parameters under `/saiman/demo/` (all SecureString except `expires-at`): exactly the ECS `valueFrom` list of
`demo-lite/containers.tf` (`ops/check-ssm-names.sh` diffs them in CI) plus `api_reader_token` and
`api_operator_token`, which no container reads: they are for you (`ops/demo-tokens.sh`).

Using the demo (your SSO profile, AWS CLI v2 + session-manager-plugin):
```bash
make demo-tunnel                      # localhost:8088 -> web container; open http://localhost:8088
make demo-capture                     # tokens from SSM into a 0700 temp dir, then make capture-demo against :8088
make demo-down-check STATE_BUCKET=<bucket>   # strict teardown check (admin profile) after demo-down
```

Teardown check: `ops/check-demo-down.sh` (exit 0 clean, 1 leftovers, 2 usage/credentials). The destroy role may not
list every service (tagging API, ELB, ECR, Cloud Map, Secrets Manager, S3 bucket list, IAM roles), so the workflow
runs it with `--allow-unverified` and prints which checks were UNVERIFIED; `make demo-down-check` is the strict run.

Permissions the bootstrap roles are missing for these workflows (follow-up for `bootstrap/iam.tf`, found while
building A4; `demo-up` fails at the asset upload until the first is added):
1. apply role: `s3:PutObject` on `<bucket>/demo/*` (assets are uploaded with `aws s3 cp --recursive`, which needs
   no listing).
2. destroy role: none needed for the reaper (it reads the expiry TAG: the roles have an explicit Deny on
   `ssm:GetParameter*`), but for a full automated check add `tag:GetResources`, `elasticloadbalancing:Describe*`,
   `ecr:DescribeRepositories`, `servicediscovery:List*`, `secretsmanager:ListSecrets`, `s3:ListAllMyBuckets` and
   `iam:ListRoles`/`iam:ListPolicies` on `*` (all read-only).
3. `CORPUS_SHA256` is a repository variable because the CI roles are denied `s3:GetObject` on `artifacts/*`, so the
   workflow cannot hash the dump itself.

Cost: about $0.55-0.80 per 4-hour session (ADR-0028), roughly $0.14-0.20 per hour; idle is about $0.003 per month
(state bucket only: `demo-down` leaves no other resource). The `infracost` job in `terraform.yml` reports the
always-on month and derives the hourly and 4-hour figures (Fargate and RDS hours have no usage knob).
