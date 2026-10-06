#!/usr/bin/env bash
# Things terraform test (mock providers) cannot see:
#   1. provider default_tags (saiman:stack, saiman:session),
#   2. "the task security group has no ingress" across every file of the module (ecs.tf from A3a
#      included): exactly one ingress rule may exist, the one on the RDS security group,
#   3. the master password never uses the stored `password` argument or Secrets Manager,
#   4. no IAM resources (roles are fixed in bootstrap), no random_password, no aws_ssm_parameter.
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

for needle in '"saiman:stack"   = "demo-lite"' '"saiman:session" = var.session_id'; do
  grep -qF "$needle" "$dir/versions.tf" || { echo "FAIL: versions.tf default_tags missing: $needle" >&2; fail=1; }
done

ingress_count="$(grep -hE '^resource "aws_vpc_security_group_ingress_rule"|^resource "aws_security_group_rule"' "$dir"/*.tf | wc -l)"
if [[ "$ingress_count" != "1" ]]; then
  echo "FAIL: expected exactly 1 ingress rule resource (rds_from_task), found $ingress_count" >&2
  fail=1
fi
if grep -nE '^[[:space:]]*ingress[[:space:]]*(=|\{)' "$dir"/*.tf >/dev/null; then
  echo "FAIL: inline ingress blocks are not allowed in demo-lite" >&2
  fail=1
fi

if grep -nE '^[[:space:]]*(password|manage_master_user_password)[[:space:]]*=' "$dir/rds.tf" >/dev/null; then
  echo "FAIL: rds.tf must use password_wo only" >&2
  fail=1
fi

# 4. IAM is fixed in bootstrap: demo-lite must not create or edit roles, policies or users (the CI apply
#    role has no such permission), nor read secret values into state.
if grep -nE '^(resource|data) "aws_iam_(role|policy|user|access_key|openid)|^(resource|data) "(random_password|aws_ssm_parameter)"' "$dir"/*.tf >/dev/null; then
  echo "FAIL: demo-lite must not declare IAM resources, random_password or aws_ssm_parameter" >&2
  fail=1
fi

[[ "$fail" == 0 ]] && echo "OK: default tags, single ingress rule, write-only password and no IAM/secret resources verified"
exit "$fail"
