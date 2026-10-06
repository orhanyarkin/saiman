#!/usr/bin/env bash
# Scrub check for `make capture-demo` (ADR-0026). Sourced by capture-demo.sh and test-scrub.sh.
#
# scrub_check <file> fails (non-zero) when the candidate capture file contains anything that must
# never be published, and prints WHICH rule matched, never the matching text:
#   - the words nonce, signature, paymentKey, privateKey, Bearer (case-insensitive, anywhere);
#   - a bare 64-hex string (a private-key or hash lookalike) that is not part of a 0x-prefixed
#     64-hex transaction hash; a 0x-prefixed hex run longer than 64 digits (a signature);
#   - any line (16+ chars, so multi-line files are covered) of any file under $SECRETS_DIR (default:
#     secrets) and any value (16+ chars) of $ENV_FILE (default .env).
# Tx hashes (0x + exactly 64 hex) are public chain data and pass.

scrub_check() {
  local file="$1" rc=0 word secret_file secrets_dir="${SECRETS_DIR:-secrets}"

  for word in nonce signature paymentKey privateKey Bearer; do
    if grep -qiF -- "${word}" "${file}"; then
      echo "scrub: FAIL: the capture contains \"${word}\"" >&2
      rc=1
    fi
  done

  # Internal service hosts (http://seller-api:8081) must be rewritten before publishing.
  if grep -qE 'http://[A-Za-z0-9_.-]+:[0-9]+' "${file}"; then
    echo "scrub: FAIL: the capture contains an internal http://<host>:<port> URL" >&2
    rc=1
  fi

  # Bare 64+ hex digits: not preceded by a hex digit or the x of a 0x prefix.
  if grep -qE '(^|[^0-9a-fA-Fx])[0-9a-fA-F]{64,}' "${file}"; then
    echo "scrub: FAIL: the capture contains a bare 64-hex string that is not a 0x-prefixed tx hash" >&2
    rc=1
  fi
  # 0x-prefixed hex that is longer than a tx hash (65+ digits), e.g. a signature.
  if grep -qE '0x[0-9a-fA-F]{65,}' "${file}"; then
    echo "scrub: FAIL: the capture contains a 0x-prefixed hex string longer than a tx hash" >&2
    rc=1
  fi

  # Secret material: every line (>= 16 chars) of every file under $SECRETS_DIR, plus the values
  # of $ENV_FILE (default .env, read-only). The patterns go through a 0600 temp file and
  # `grep -F -f`, never through argv, and are never printed.
  local patterns env_file="${ENV_FILE:-.env}"
  patterns="$(umask 077 && mktemp "${TMPDIR:-/tmp}/saiman-scrub.XXXXXX")"
  if [[ -d "${secrets_dir}" ]]; then
    for secret_file in "${secrets_dir}"/*; do
      [[ -f "${secret_file}" && -s "${secret_file}" ]] || continue
      tr -d '\r' <"${secret_file}" | awk 'length($0) >= 16' >>"${patterns}"
    done
  fi
  if [[ -r "${env_file}" ]]; then
    # KEY=value lines: value without export/quotes/trailing comment.
    awk '
      { line = $0; sub(/^[ \t]*(export[ \t]+)?/, "", line) }
      line ~ /^[A-Za-z_][A-Za-z0-9_]*=/ {
        v = substr(line, index(line, "=") + 1)
        sub(/[ \t]+#.*$/, "", v); gsub(/^[ \t"\047]+|[ \t"\047\r]+$/, "", v)
        if (length(v) >= 16) print v
      }' "${env_file}" >>"${patterns}"
  fi
  if [[ -s "${patterns}" ]] && grep -qFf "${patterns}" "${file}"; then
    echo "scrub: FAIL: the capture contains a value from a secrets file or the env file" >&2
    rc=1
  fi
  rm -f "${patterns}"

  return "${rc}"
}
