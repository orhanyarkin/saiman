#!/usr/bin/env bash
# Removes what Terraform does not own after `terraform destroy` (ADR-0028), with the destroy role:
#   1. SSM parameters under /saiman/demo/ (created by demo-up, never by Terraform) EXCEPT expires-at, which
#      is kept until the teardown check passed (--expiry-only), so a failed teardown is retried by the reaper;
#   2. every object version and delete marker under s3://<state bucket>/demo/ (the bucket is versioned);
#   3. ACTIVE task definitions are deregistered, INACTIVE ones deleted, as far as the role allows.
# Idempotent. Prints names and counts only, never parameter values.
#
# Usage: demo-cleanup.sh --region eu-central-1 --state-bucket <bucket> [--expiry-only]
set -euo pipefail

region=""
expiry_only=0
bucket=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      [[ $# -ge 2 ]] || exit 2
      region="$2"
      shift 2
      ;;
    --state-bucket)
      [[ $# -ge 2 ]] || exit 2
      bucket="$2"
      shift 2
      ;;
    --expiry-only)
      expiry_only=1
      shift
      ;;
    *)
      echo "usage: demo-cleanup.sh --region <region> --state-bucket <bucket>" >&2
      exit 2
      ;;
  esac
done
[[ -n "${region}" && -n "${bucket}" ]] || {
  echo "usage: demo-cleanup.sh --region <region> --state-bucket <bucket>" >&2
  exit 2
}

export AWS_PAGER=""
if [[ "${expiry_only}" -eq 1 ]]; then
  # Last step of demo-destroy, only after check-demo-down passed: until then the reaper stays armed.
  if aws --region "${region}" --output json ssm describe-parameters \
    --parameter-filters "Key=Name,Option=Equals,Values=/saiman/demo/expires-at" | jq -e '(.Parameters // []) | length > 0' >/dev/null; then
    aws --region "${region}" --output json ssm delete-parameter --name /saiman/demo/expires-at >/dev/null
    echo "demo-cleanup: deleted /saiman/demo/expires-at"
  fi
  exit 0
fi
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-cleanup.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

aws_json() {
  aws --region "${region}" --output json "$@"
}

# --- 1. SSM parameters -------------------------------------------------------------------------------
aws_json ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/saiman/demo/" |
  jq -r '.Parameters[]?.Name' >"${work}/params.txt"
# Everything except expires-at first, in batches of 10 (the API limit), then expires-at itself.
grep -vxF '/saiman/demo/expires-at' "${work}/params.txt" >"${work}/params-main.txt" || true
deleted=0
while [[ -s "${work}/params-main.txt" ]]; do
  head -n 10 "${work}/params-main.txt" >"${work}/batch.txt"
  tail -n +11 "${work}/params-main.txt" >"${work}/rest.txt"
  mv "${work}/rest.txt" "${work}/params-main.txt"
  mapfile -t batch <"${work}/batch.txt"
  aws_json ssm delete-parameters --names "${batch[@]}" >/dev/null
  deleted=$((deleted + ${#batch[@]}))
done
echo "demo-cleanup: deleted ${deleted} SSM parameter(s) under /saiman/demo/"

# --- 2. S3 demo/ prefix (all versions and delete markers) -----------------------------------------------
removed=0
for _ in $(seq 1 200); do
  aws_json s3api list-object-versions --bucket "${bucket}" --prefix demo/ --max-items 1000 >"${work}/versions.json"
  count="$(jq '[(.Versions // [])[], (.DeleteMarkers // [])[]] | length' "${work}/versions.json")"
  [[ "${count}" -gt 0 ]] || break
  jq '{Quiet: true, Objects: [(.Versions // [])[], (.DeleteMarkers // [])[] | {Key: .Key, VersionId: .VersionId}]}' \
    "${work}/versions.json" >"${work}/delete.json"
  aws_json s3api delete-objects --bucket "${bucket}" --delete "file://${work}/delete.json" >/dev/null
  removed=$((removed + count))
done
echo "demo-cleanup: removed ${removed} object version(s) under s3://${bucket}/demo/"

# --- 3. task definitions ---------------------------------------------------------------------------------
aws_json ecs list-task-definitions --family-prefix saiman-demo --status ACTIVE | jq -r '.taskDefinitionArns[]?' >"${work}/active.txt"
deregistered=0
while IFS= read -r arn; do
  [[ -n "${arn}" ]] || continue
  if aws_json ecs deregister-task-definition --task-definition "${arn}" >/dev/null; then
    deregistered=$((deregistered + 1))
  else
    echo "demo-cleanup: could not deregister ${arn##*/} (role limit?); it stays ACTIVE" >&2
  fi
done <"${work}/active.txt"
aws_json ecs list-task-definitions --family-prefix saiman-demo --status INACTIVE | jq -r '.taskDefinitionArns[]?' >"${work}/inactive.txt"
deleted_td=0
while [[ -s "${work}/inactive.txt" ]]; do
  head -n 10 "${work}/inactive.txt" >"${work}/batch.txt"
  tail -n +11 "${work}/inactive.txt" >"${work}/rest.txt"
  mv "${work}/rest.txt" "${work}/inactive.txt"
  mapfile -t batch <"${work}/batch.txt"
  if aws_json ecs delete-task-definitions --task-definitions "${batch[@]}" >/dev/null; then
    deleted_td=$((deleted_td + ${#batch[@]}))
  else
    echo "demo-cleanup: could not delete ${#batch[@]} INACTIVE task definition(s); harmless, they cost nothing" >&2
  fi
done
echo "demo-cleanup: deregistered ${deregistered} task definition(s), deleted ${deleted_td} INACTIVE one(s)"

echo "demo-cleanup: /saiman/demo/expires-at kept; run with --expiry-only once the teardown check passed"
