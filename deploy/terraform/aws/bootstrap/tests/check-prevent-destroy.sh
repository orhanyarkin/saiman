#!/usr/bin/env bash
# terraform test cannot see lifecycle meta-arguments, so assert that the state bucket resource keeps
# `prevent_destroy = true` by reading main.tf. Exit 1 if it is missing or the bucket block is gone.
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
file="$dir/main.tf"

found="$(awk '
  /^resource "aws_s3_bucket" "state" \{/ { inside = 1; next }
  inside && /^\}/ { inside = 0 }
  inside && /^[[:space:]]*prevent_destroy[[:space:]]*=[[:space:]]*true[[:space:]]*$/ { print "yes" }
' "$file")"

if [[ "$found" != "yes" ]]; then
  echo "FAIL: aws_s3_bucket.state in $file must set lifecycle { prevent_destroy = true }" >&2
  exit 1
fi
echo "OK: aws_s3_bucket.state has prevent_destroy = true"
