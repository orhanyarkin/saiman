#!/usr/bin/env bash
# Builds the per-run secrets directory for `make ingest-backfill`: a fresh 0700 directory
# holding copies (mode 0644, like the compose secret mounts) of ONLY what ingest may read --
# mkk_credentials and openai_api_key (ADR-0009) plus its own two database passwords, renamed to
# the properties they set (ADR-0024): pg_ingest_app_password -> spring.datasource.password and
# pg_ingest_owner_password -> spring.flyway.password. The JVM loads this directory as a
# configtree, so pointing it at the whole secrets/ would turn buyer.key into a property
# inside the ingest process. Never prints file contents.
#
# Usage: scripts/prepare-ingest-secrets.sh [SRC_DIR] [DEST_DIR]
set -euo pipefail

src="${1:-secrets}"
dest="${2:-build/ingest-secrets}"

rm -rf "${dest}"
mkdir -p "${dest}"
chmod 700 "${dest}"

# <source file>:<destination file>
for mapping in \
  mkk_credentials:mkk_credentials \
  openai_api_key:openai_api_key \
  pg_ingest_app_password:spring.datasource.password \
  pg_ingest_owner_password:spring.flyway.password; do
  name="${mapping%%:*}"
  target="${mapping##*:}"
  if [[ -f "${src}/${name}" ]]; then
    cp "${src}/${name}" "${dest}/${target}"
    chmod 644 "${dest}/${target}"
  else
    echo "prepare-ingest-secrets: ${src}/${name} is missing (run 'make secrets-check' / 'scripts/ensure-secret-files.sh')" >&2
  fi
done
