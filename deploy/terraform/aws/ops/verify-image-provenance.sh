#!/usr/bin/env bash
# Image provenance gate for demo-up (audit M1). The GHCR tag sha-<12> is mutable: any workflow with
# `packages: write` could re-push it. The ci.yml `images-manifest` job on the main push run records the
# index digests it created (artifact `image-digests`, digests.json). This script finds that run for the
# commit, downloads digests.json and REQUIRES the digests GHCR resolves today to equal the recorded ones.
# The recorded digests are what is passed to Terraform. Runs before any secret or credential exists.
#
# Usage: verify-image-provenance.sh <full-commit-sha> <out-digests.json>
# Needs `gh` (GH_TOKEN with actions: read) and docker buildx. Exit 0 verified, 1 mismatch/missing, 2 usage.
set -euo pipefail

[[ $# -eq 2 && "$1" =~ ^[0-9a-f]{40}$ ]] || {
  echo "usage: verify-image-provenance.sh <40-hex commit sha> <out-digests.json>" >&2
  exit 2
}
sha="$1"
out_file="$2"
tag="sha-${sha:0:12}"
services=(orchestrator seller-api ledger ingest evals)
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-provenance.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

fail() {
  echo "::error::image provenance: $*" >&2
  exit 1
}

run_id="$(gh run list --workflow ci.yml --branch main --event push --commit "${sha}" --status success --limit 1 \
  --json databaseId --jq '.[0].databaseId // empty')"
[[ -n "${run_id}" ]] || fail "no successful ci.yml push run on main for ${sha:0:12}; images for it were not published by main CI"
gh run download "${run_id}" --name image-digests --dir "${work}" || fail "run ${run_id} has no image-digests artifact (expired after 30 days?)"
recorded="${work}/digests.json"
[[ -s "${recorded}" ]] || fail "digests.json missing in run ${run_id}"
[[ "$(jq -r '.commit' "${recorded}")" == "${sha}" ]] || fail "digests.json belongs to a different commit"

for svc in "${services[@]}"; do
  want="$(jq -r --arg s "${svc}" '.digests[$s] // empty' "${recorded}")"
  [[ "${want}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "no recorded digest for ${svc}"
  have="$(docker buildx imagetools inspect "ghcr.io/orhanyarkin/saiman-${svc}:${tag}" --format '{{.Manifest.Digest}}')" ||
    fail "cannot inspect ghcr.io/orhanyarkin/saiman-${svc}:${tag} (public package?)"
  [[ "${have}" == "${want}" ]] || fail "${svc}:${tag} resolves to ${have} but main CI recorded ${want}: the tag was re-pushed"
  echo "ok ${svc} ${want}"
done
jq -c '.digests' "${recorded}" >"${out_file}"
echo "image provenance verified against ci.yml run ${run_id}"
