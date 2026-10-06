#!/usr/bin/env bash
# Reads the demo expiry for the reaper and for demo-destroy's re-check under the lock (ADR-0028).
#
# demo-up stores the expiry twice: as the value of the SSM parameter /saiman/demo/expires-at and as its
# `expires-at` TAG. The CI roles have an explicit Deny on ssm:GetParameter* for /saiman/* (so PR code
# can never read a secret), hence the reaper reads the tag: ssm:DescribeParameters and
# ssm:ListTagsForResource are plain read permissions of the destroy role.
#
# Usage: demo-expiry.sh --region eu-central-1 [--now <RFC 3339 UTC>]
# Prints three lines on stdout (append them to $GITHUB_OUTPUT):
#   exists=true|false   expires_at=<ISO or empty>   expired=true|false
# exists=false (no parameter) means "no demo is running that this workflow knows about": nothing to do.
# A parameter without a parseable tag counts as expired (fail towards destroying: it is only cost).
# Exit 0 on success, 2 on usage or AWS errors.
set -euo pipefail

region=""
now=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      [[ $# -ge 2 ]] || exit 2
      region="$2"
      shift 2
      ;;
    --now)
      [[ $# -ge 2 ]] || exit 2
      now="$2"
      shift 2
      ;;
    *)
      echo "usage: demo-expiry.sh --region <region> [--now <RFC 3339 UTC>]" >&2
      exit 2
      ;;
  esac
done
[[ -n "${region}" ]] || {
  echo "usage: demo-expiry.sh --region <region> [--now <RFC 3339 UTC>]" >&2
  exit 2
}
export AWS_PAGER=""
now_epoch="$(date -u -d "${now:-now}" +%s)"

described="$(aws --region "${region}" --output json ssm describe-parameters \
  --parameter-filters "Key=Name,Option=Equals,Values=/saiman/demo/expires-at")"
found="$(jq -r '[.Parameters[]?.Name] | length' <<<"${described}")"
if [[ "${found}" -eq 0 ]]; then
  printf 'exists=false\nexpires_at=\nexpired=false\n'
  exit 0
fi

expires_at="$(aws --region "${region}" --output json ssm list-tags-for-resource \
  --resource-type Parameter --resource-id /saiman/demo/expires-at |
  jq -r '(.TagList // [])[] | select(.Key == "expires-at") | .Value' | head -n 1)"

expired=true
if [[ "${expires_at}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
  if exp_epoch="$(date -u -d "${expires_at}" +%s 2>/dev/null)" && [[ "${exp_epoch}" -gt "${now_epoch}" ]]; then
    expired=false
  fi
else
  expires_at=""
fi
# Age cap (L2): the longest demo is 8 h (+30 min grace). A parameter last modified longer ago than that is
# expired whatever its tag says (a tag set to 2099 by someone with write access cannot keep a demo alive).
modified="$(jq -r '.Parameters[0].LastModifiedDate // empty' <<<"${described}")"
if [[ -n "${modified}" ]]; then
  if [[ "${modified}" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then mod_epoch="${modified%.*}"; else mod_epoch="$(date -u -d "${modified}" +%s 2>/dev/null || echo "")"; fi
  if [[ -n "${mod_epoch}" && "${now_epoch}" -gt $((mod_epoch + 30600)) ]]; then
    expired=true
  fi
fi
printf 'exists=true\nexpires_at=%s\nexpired=%s\n' "${expires_at}" "${expired}"
