#!/usr/bin/env bash
# Prints the aggregate sha256 of a directory tree: sha256 over the sorted `sha256  path` manifest of its files.
# demo-up builds the web dist in an unprivileged job and verifies this digest in the privileged one (M1).
# Usage: dist-digest.sh <dir>
set -euo pipefail

[[ $# -eq 1 && -d "$1" ]] || {
  echo "usage: dist-digest.sh <dir>" >&2
  exit 2
}
cd "$1"
[[ -f index.html ]] || {
  echo "dist-digest: $1 has no index.html" >&2
  exit 2
}
find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1
