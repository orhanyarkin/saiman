#!/usr/bin/env bash
# Prints NAME=value lines (append them to $GITHUB_ENV) with NON-SECRET placeholder inputs for demo-lite.
# Used where Terraform needs the required variables to evaluate the configuration but their values do not
# matter: `terraform plan` on pull requests (read-only role) and `terraform destroy` (the state decides
# what exists; variables only have to pass validation). Real values exist only in demo-up.
#
# Usage: tf-dummy-env.sh <state-bucket>
# db_master_password is ephemeral and null by default: deliberately NOT set (never in plan or state).
set -euo pipefail

[[ $# -eq 1 && "$1" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || {
  echo "usage: tf-dummy-env.sh <state-bucket>" >&2
  exit 2
}
zeros64="$(printf '0%.0s' {1..64})"
cat <<EOF
TF_VAR_image_tag=sha-000000000000
TF_VAR_session_id=placeholder
TF_VAR_expires_at=2099-01-01T00:00:00Z
TF_VAR_x402_seller_payto_address=0x0000000000000000000000000000000000000001
TF_VAR_auth_digests={"reader":"${zeros64}","operator":"${zeros64}","service_ledger":"${zeros64}"}
TF_VAR_state_bucket_name=$1
TF_VAR_image_digests={"orchestrator":"sha256:${zeros64}","seller-api":"sha256:${zeros64}","ledger":"sha256:${zeros64}","ingest":"sha256:${zeros64}","evals":"sha256:${zeros64}"}
TF_VAR_assets_manifest_sha256=${zeros64}
TF_VAR_corpus_sha256=${zeros64}
EOF
