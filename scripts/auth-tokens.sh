#!/usr/bin/env bash
# Where the API tokens live and how to use one (ADR-0023). Prints NO token by default.
#
#   scripts/auth-tokens.sh              list the token files (mode, size), never their content
#   scripts/auth-tokens.sh copy <role>  copy secrets/api_<role>_token to the clipboard
#                                       (clip.exe, wl-copy, xclip or pbcopy, whichever exists)
#   scripts/auth-tokens.sh show <role>  print the token to stdout (explicit request only; keep
#                                       it out of shared terminals and shell history)
# <role> is reader or operator. Service tokens are machine-to-machine and have no copy/show.
set -euo pipefail

dir="${SECRETS_DIR:-secrets}"

list() {
  local name file
  echo "API token files (raw tokens; the services hold only their SHA-256 digests):"
  for name in api_reader_token api_operator_token seller_service_token_ledger seller_service_token_evals; do
    file="${dir}/${name}"
    if [[ -s "${file}" ]]; then
      printf '  %-30s mode %s, %s bytes\n' "${file}" "$(stat -c %a "${file}")" "$(stat -c %s "${file}")"
    else
      printf '  %-30s missing or empty (run make up)\n' "${file}"
    fi
  done
  cat <<'EOF'

Paste the READER or OPERATOR token into the dashboard's "Connect" dialog (http://localhost:8088).
  make auth-token-copy ROLE=reader     copy the reader token to the clipboard
  make auth-token-show ROLE=operator   print the operator token (explicit request)
Roles: READER = all GETs; OPERATOR = also start runs, decide approvals, trigger reconciliation.
EOF
}

role_file() {
  case "${1:-}" in
    reader | operator) echo "${dir}/api_${1}_token" ;;
    *)
      echo "auth-tokens: role must be reader or operator" >&2
      exit 2
      ;;
  esac
}

case "${1:-list}" in
  list) list ;;
  copy)
    file="$(role_file "${2:-}")"
    [[ -s "${file}" ]] || {
      echo "auth-tokens: ${file} is missing or empty" >&2
      exit 1
    }
    for tool in clip.exe wl-copy "xclip -selection clipboard" pbcopy; do
      # shellcheck disable=SC2086
      if command -v "${tool%% *}" >/dev/null 2>&1; then
        tr -d '\r\n' <"${file}" | ${tool}
        echo "auth-tokens: ${2} token copied to the clipboard"
        exit 0
      fi
    done
    echo "auth-tokens: no clipboard tool found (clip.exe, wl-copy, xclip, pbcopy); use 'make auth-token-show ROLE=${2}'" >&2
    exit 1
    ;;
  show)
    file="$(role_file "${2:-}")"
    [[ -s "${file}" ]] || {
      echo "auth-tokens: ${file} is missing or empty" >&2
      exit 1
    }
    tr -d '\r\n' <"${file}"
    echo
    ;;
  *)
    echo "usage: auth-tokens.sh [list | copy reader|operator | show reader|operator]" >&2
    exit 2
    ;;
esac
