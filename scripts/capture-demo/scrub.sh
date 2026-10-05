#!/usr/bin/env bash
# Scrub check for `make capture-demo` (ADR-0026). Sourced by capture-demo.sh and test-scrub.sh.
#
# scrub_check <file> fails (non-zero) when the candidate capture file contains anything that must
# never be published, and prints WHICH rule matched, never the matching text:
#   - the words nonce, signature, paymentKey, privateKey, Bearer (case-insensitive, anywhere);
#   - a bare 64-hex string (a private-key or hash lookalike) that is not part of a 0x-prefixed
#     64-hex transaction hash; a 0x-prefixed hex run longer than 64 digits (a signature);
#   - the content of any secret file under $SECRETS_DIR (default: secrets), for files of at least
#     16 characters (DB passwords, API tokens, API keys, the buyer key).
# Tx hashes (0x + exactly 64 hex) are public chain data and pass.

scrub_check() {
  local file="$1" rc=0 word secret_file secrets_dir="${SECRETS_DIR:-secrets}"

  for word in nonce signature paymentKey privateKey Bearer; do
    if grep -qiF -- "${word}" "${file}"; then
      echo "scrub: FAIL: the capture contains \"${word}\"" >&2
      rc=1
    fi
  done

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

  if [[ -d "${secrets_dir}" ]]; then
    for secret_file in "${secrets_dir}"/*; do
      [[ -f "${secret_file}" && -s "${secret_file}" ]] || continue
      # Only whole-file content of reasonable length; trailing newline removed.
      local content
      content="$(tr -d '\r\n' <"${secret_file}")"
      [[ ${#content} -ge 16 ]] || continue
      if grep -qF -- "${content}" "${file}"; then
        echo "scrub: FAIL: the capture contains the content of ${secret_file}" >&2
        rc=1
      fi
    done
  fi

  return "${rc}"
}
