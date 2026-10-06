#!/usr/bin/env bash
# Things terraform test (mock providers) cannot see in the ECS task (ecs.tf, containers.tf):
#   1. the only data source is aws_caller_identity: SSM values are never read (secrets are ARN strings),
#   2. no IAM, random_password, aws_ssm_parameter or Secrets Manager resources anywhere,
#   3. the buyer key parameter appears exactly once, inside app_secrets.orchestrator,
#   4. the ECS service and task keep the flags the destroy role and the human depend on.
# (Which environment names and secrets reach which container is asserted by task.tftest.hcl.)
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0
err() {
  echo "FAIL: $*" >&2
  fail=1
}

extra_data="$(grep -hE '^data "' "$dir"/*.tf | grep -v '^data "aws_caller_identity"' || true)"
[[ -z "$extra_data" ]] || err "unexpected data source(s): $extra_data"

if grep -nE '^(resource|data) "aws_iam_|^(resource|data) "(random_password|aws_ssm_parameter|aws_secretsmanager_secret(_version)?)"' "$dir"/*.tf >/dev/null; then
  err "IAM, random_password, aws_ssm_parameter and Secrets Manager resources are forbidden in demo-lite"
fi

if [[ "$(grep -c 'x402_buyer_private_key' "$dir/containers.tf")" != "1" ]]; then
  err "the buyer key parameter must appear exactly once in containers.tf"
elif ! awk '/^  app_secrets = \{/{s=1} s&&/^    orchestrator = \{/{o=1} o&&/x402_buyer_private_key/{found=1} /^    seller-api = \{/{o=0} END{exit !found}' "$dir/containers.tf"; then
  err "the buyer key must be in app_secrets.orchestrator"
fi

for needle in 'cpu_architecture        = "ARM64"' 'force_delete = true' 'enable_execute_command = true' 'wait_for_steady_state = false'; do
  grep -qF "$needle" "$dir/ecs.tf" || err "ecs.tf is missing: $needle"
done

[[ "$fail" == 0 ]] && echo "OK: no secret reads, no IAM, buyer key only on orchestrator, ECS flags verified"
exit "$fail"
