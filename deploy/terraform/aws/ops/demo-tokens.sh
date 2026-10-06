#!/usr/bin/env bash
# Fetches the demo's API tokens (reader, operator) from SSM into a fresh 0700 directory so that
# scripts/capture-demo/ (and curl-auth.sh) can use them, ADR-0023 / ADR-0028. Needs YOUR SSO profile with
# ssm:GetParameter on /saiman/demo/*: the CI roles are explicitly denied that. Prints ONLY paths.
#
# Usage: demo-tokens.sh [--region eu-central-1]
# Prints the directory (one line on stdout, for `SECRETS_DIR=$(ops/demo-tokens.sh)`); the files are
# api_reader_token and api_operator_token (0600). Delete the directory when done (make demo-capture does).
set -euo pipefail

region="eu-central-1"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      [[ $# -ge 2 ]] || exit 2
      region="$2"
      shift 2
      ;;
    *)
      echo "usage: demo-tokens.sh [--region <region>]" >&2
      exit 2
      ;;
  esac
done
export AWS_PAGER=""
umask 077
dir="$(mktemp -d "${TMPDIR:-/tmp}/saiman-demo-tokens.XXXXXX")"
chmod 700 "${dir}"
for name in api_reader_token api_operator_token; do
  # Straight into the file: the value never passes through a shell variable or the terminal.
  if ! aws --region "${region}" ssm get-parameter --name "/saiman/demo/${name}" --with-decryption \
    --query Parameter.Value --output text >"${dir}/${name}"; then
    rm -rf "${dir}"
    echo "demo-tokens: could not read /saiman/demo/${name} (is the demo up, and is this your admin SSO profile?)" >&2
    exit 1
  fi
  # `--output text` ends with a newline; the services hash the token without CR/LF.
  tr -d '\r\n' <"${dir}/${name}" >"${dir}/${name}.tmp"
  mv "${dir}/${name}.tmp" "${dir}/${name}"
  chmod 600 "${dir}/${name}"
done
echo "${dir}"
echo "demo-tokens: wrote ${dir}/api_reader_token and ${dir}/api_operator_token (0600)" >&2
