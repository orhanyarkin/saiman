#!/usr/bin/env bash
# Runs the four Terraform/ops mutation checks (what `make tf-mutation-check` and the `mutation` job of
# .github/workflows/terraform.yml run), offline: the credential environment is scrubbed, nothing here talks to AWS.
#
# Every check runs even if an earlier one fails, so one run shows all problems. Exit codes of the checks:
#   0 all mutations caught, 1 a mutation survived (a rule lost its test) or the baseline is red,
#   2 SETUP: a mutation anchor no longer matches the file it edits (the text changed: update the mutation table).
# This script exits 0 only if all four exit 0, otherwise with the highest code seen (2 beats 1).
# Run from anywhere. Needs python3 and terraform on PATH.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
aws_dir="$(cd "${here}/.." && pwd)"

checks=(
  "${aws_dir}/demo-lite/tests/mutation-check.py"
  "${aws_dir}/bootstrap/tests/mutation-check.py"
  "${aws_dir}/ops/mutation-check-demo-down.py"
  "${aws_dir}/ops/mutation-check-tunnel-readiness.py"
)

worst=0
for check in "${checks[@]}"; do
  name="${check#"${aws_dir}"/}"
  echo "::group::mutation check ${name}"
  env -u AWS_PROFILE -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
    AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true \
    python3 -B "${check}"
  rc=$?
  echo "::endgroup::"
  case "${rc}" in
    0) echo "ok: ${name}: all mutations caught" ;;
    2) echo "::error title=Stale mutation anchor::${name} exited 2 (SETUP). A mutation anchor no longer matches the file it edits, usually because a dependency bump changed a version in the anchored text. Not a flake: fix the anchor in ${name} so it carries no version, digest or date, then re-run." ;;
    *) echo "::error title=Mutation check failed::${name} exited ${rc}: a mutation survived (a rule lost its test) or the baseline is red. Read the SURVIVED lines above." ;;
  esac
  if [[ "${rc}" -gt "${worst}" ]]; then
    worst="${rc}"
  fi
done

exit "${worst}"
