#!/usr/bin/env bash
# curl with an `Authorization: Bearer` header taken from secrets/api_<role>_token (ADR-0023).
# The header goes through a 0600 temporary file (`curl -H @file`), so the token never appears in
# argv (`ps`), in make's echoed recipe lines or in logs; the file is removed on exit.
#
# Usage: scripts/curl-auth.sh reader|operator <curl arguments...>
set -euo pipefail

role="${1:-}"
case "${role}" in
  reader | operator) shift ;;
  *)
    echo "usage: curl-auth.sh reader|operator <curl arguments...>" >&2
    exit 2
    ;;
esac

# Flags that could print the Authorization header (verbose/trace output, dumped headers, config
# files, library dumps) are refused: the token must never reach a terminal or a log.
for arg in "$@"; do
  case "${arg}" in
    -v | --verbose | --trace | --trace-ascii | --trace-time | --trace-config | --trace-ids | -D* | --dump-header | -K | --config | --libcurl | --stderr)
      echo "curl-auth: refusing curl flag ${arg} (it could print the Authorization header)" >&2
      exit 2
      ;;
  esac
  if [[ "${arg}" =~ ^-[A-Za-z]*[vDK][A-Za-z]*$ ]]; then
    echo "curl-auth: refusing curl flag cluster ${arg} (contains -v, -D or -K)" >&2
    exit 2
  fi
done

token_file="${SECRETS_DIR:-secrets}/api_${role}_token"
if [[ ! -s "${token_file}" ]]; then
  echo "curl-auth: ${token_file} is missing or empty (run 'make up' or scripts/ensure-secret-files.sh)" >&2
  exit 1
fi

umask 077
header_file="$(mktemp "${TMPDIR:-/tmp}/saiman-auth-header.XXXXXX")"
trap 'rm -f "${header_file}"' EXIT
{
  printf 'Authorization: Bearer '
  tr -d '\r\n' <"${token_file}"
  printf '\n'
} >"${header_file}"

# Not exec: the trap must run to delete the header file.
curl -H "@${header_file}" "$@"
