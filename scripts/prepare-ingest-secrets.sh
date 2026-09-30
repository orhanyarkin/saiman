#!/usr/bin/env bash
# Builds the per-run secrets directory for `make ingest-backfill`: a fresh 0700 directory
# holding copies (mode 0644, like the compose secret mounts) of ONLY the files ingest may
# read -- mkk_credentials and openai_api_key (ADR-0009). The JVM loads this directory as a
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

for name in mkk_credentials openai_api_key; do
  if [[ -f "${src}/${name}" ]]; then
    cp "${src}/${name}" "${dest}/${name}"
    chmod 644 "${dest}/${name}"
  else
    echo "prepare-ingest-secrets: ${src}/${name} is missing (run 'make secrets-check')" >&2
  fi
done
