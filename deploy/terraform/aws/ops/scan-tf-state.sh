#!/usr/bin/env bash
# Scans `terraform show -json` output (plan or state) for secret material (ADR-0028: Terraform must never
# see a secret). Fails loudly and names the KIND that matched (the secret file's name), never the value.
#
# Usage: scan-tf-state.sh <secrets-dir> <terraform-show.json>
#   <secrets-dir>  every non-empty file in it is a secret (the demo-up generated secrets, the buyer key,
#                  the OpenAI key, the db master password source file). Each file's lines of 16+ characters
#                  are the patterns; they travel through a 0600 temp file and `grep -F -f`, never argv.
# Plus generic shapes: PEM private keys and OpenAI-style keys. The 64-hex rule of scripts/capture-demo/
# scrub.sh is NOT reused: the auth digests (public SHA-256 of the tokens) are legitimately in the plan.
# Exit 0 clean, 1 secret found, 2 usage error.
set -euo pipefail

[[ $# -eq 2 && -d "$1" && -f "$2" ]] || {
  echo "usage: scan-tf-state.sh <secrets-dir> <terraform-show.json>" >&2
  exit 2
}
dir="$1"
json="$2"
rc=0
umask 077
pattern_file="$(mktemp "${TMPDIR:-/tmp}/saiman-scan.XXXXXX")"
trap 'rm -f "${pattern_file}"' EXIT

checked=0
for f in "${dir}"/*; do
  [[ -f "${f}" && -s "${f}" ]] || continue
  tr -d '\r' <"${f}" | awk 'length($0) >= 16' >"${pattern_file}"
  # A 0x-prefixed key is also searched without the prefix (state may hold either spelling).
  sed -nE "s/^0[xX]([0-9a-fA-F]{64})$/\1/p" "${pattern_file}" >>"${pattern_file}.x" && cat "${pattern_file}.x" >>"${pattern_file}" && rm -f "${pattern_file}.x"
  [[ -s "${pattern_file}" ]] || continue
  checked=$((checked + 1))
  if grep -qiFf "${pattern_file}" "${json}"; then
    echo "scan-tf-state: FAIL: the Terraform output contains the value of secret '$(basename "${f}")'" >&2
    rc=1
  fi
done

if grep -qE -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' "${json}"; then
  echo "scan-tf-state: FAIL: the Terraform output contains a PEM private key" >&2
  rc=1
fi
if grep -qE 'sk-[A-Za-z0-9_-]{20,}' "${json}"; then
  echo "scan-tf-state: FAIL: the Terraform output contains an OpenAI-style API key" >&2
  rc=1
fi
if grep -qE '"(private_key|password|secret_key)"[[:space:]]*:[[:space:]]*"[^"]{8,}"' "${json}"; then
  echo "scan-tf-state: FAIL: the Terraform output has a non-empty password, private_key or secret_key attribute" >&2
  rc=1
fi

if [[ "${checked}" -eq 0 ]]; then
  echo "scan-tf-state: no secret files with 16+ character lines in ${dir}: nothing to compare against" >&2
  exit 2
fi
if [[ "${rc}" -eq 0 ]]; then
  echo "scan-tf-state: clean (${checked} secret file(s) compared, generic key shapes absent)"
fi
exit "${rc}"
