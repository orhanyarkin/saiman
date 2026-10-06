#!/usr/bin/env bash
# `terraform init` of demo-lite against the remote S3 state of the bootstrap bucket (ADR-0028).
# demo-lite declares no backend (CI validates it with -backend=false and tests run on local state), so a
# throwaway `ci_backend.tf` with an empty s3 backend is written into the CI checkout and configured with
# -backend-config. Native S3 locking (use_lockfile), no DynamoDB. The file is never committed.
#
# Usage: tf-init-demo-lite.sh <state-bucket>     (run from anywhere; needs AWS credentials in the environment)
set -euo pipefail

[[ $# -eq 1 && "$1" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || {
  echo "usage: tf-init-demo-lite.sh <state-bucket>" >&2
  exit 2
}
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dir="$(cd "${here}/../demo-lite" && pwd)"
cat >"${dir}/ci_backend.tf" <<'EOF'
terraform {
  backend "s3" {}
}
EOF
terraform -chdir="${dir}" init -input=false -reconfigure \
  -backend-config="bucket=$1" \
  -backend-config="key=demo-lite/terraform.tfstate" \
  -backend-config="region=eu-central-1" \
  -backend-config="use_lockfile=true" \
  -backend-config="encrypt=true"
