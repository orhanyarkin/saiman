#!/usr/bin/env bash
# For the HUMAN to run (`make secrets-from-dotenv`): copies exactly one variable,
# OPENAI_API_KEY, from the repo-root .env into secrets/openai_api_key (mode 0644 in the 0700 dir, atomic
# write) so the containers can read it as a compose secret file (ADR-0009 M2 amendment).
# Reads only that allowlisted key; prints only "written (N bytes)" -- never the value,
# not even in error messages (only its length or shape is ever reported).
#
# Overrides (used by the self-test): ENV_FILE (default .env), SECRETS_DIR (default
# secrets). Refuses to overwrite an existing non-empty file unless FORCE=1.
set -euo pipefail

readonly KEY="OPENAI_API_KEY"
env_file="${ENV_FILE:-.env}"
dir="${SECRETS_DIR:-secrets}"
target="${dir}/openai_api_key"

die() {
  echo "secrets-from-dotenv: $*" >&2
  exit 1
}

[[ -r "$env_file" ]] || die "cannot read ${env_file}"

if [[ -s "$target" && "${FORCE:-0}" != "1" ]]; then
  die "${target} already exists and is not empty; re-run with FORCE=1 to overwrite"
fi

if [[ ! -d "$dir" ]]; then
  (umask 077 && mkdir -p "$dir")
  chmod 700 "$dir"
fi

tmp="$(umask 077 && mktemp "${dir}/.openai_api_key.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

# Last assignment wins. Strips `export `, surrounding whitespace, one pair of quotes and a
# trailing ` # comment`. The value goes straight from awk into the 0600 temp file: it is
# never held in a shell variable, argv or the terminal.
awk -v key="$KEY" '
  {
    line = $0
    sub(/^[ \t]*(export[ \t]+)?/, "", line)
    if (index(line, key "=") != 1) next
    value = substr(line, length(key) + 2)
    sub(/[ \t]+#.*$/, "", value)
    gsub(/^[ \t\r]+|[ \t\r]+$/, "", value)
    if (value ~ /^".*"$/ || value ~ /^\047.*\047$/) value = substr(value, 2, length(value) - 2)
    found = value
  }
  END { printf "%s", found }
' "$env_file" >"$tmp"

bytes="$(wc -c <"$tmp" | tr -d ' ')"
if [[ "$bytes" -eq 0 ]]; then
  die "${KEY} is missing or empty in ${env_file}"
fi
# Shape check only (length reported, never content): a real key has no whitespace.
if LC_ALL=C grep -q '[[:space:]]' "$tmp"; then
  die "${KEY} value (${bytes} bytes) contains whitespace; not written"
fi

# 0644 inside the 0700 dir: the container user's uid differs from the host user's, so a
# 0600 file would be unreadable in the container (the directory mode is the protection).
chmod 644 "$tmp"
mv -f "$tmp" "$target"
trap - EXIT
echo "secrets-from-dotenv: written (${bytes} bytes)"
