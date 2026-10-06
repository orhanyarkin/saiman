#!/usr/bin/env bash
# `make eval` (ADR-0025): runs the `evals` compose one-shot (profile `evals`) on the compose
# network, then publishes its reports from build/evals into docs/evals/:
#   latest.md, latest.json            the newest report
#   runs/<date>-<sha>.json            the same JSON, kept per run (date = UTC, sha = short git HEAD)
# EVAL_ANSWERS=1 enables the answer tier (SAIMAN_EVALS_ANSWERS_ENABLED=true): it calls the
# seller-api's internal eval endpoint, which spends LLM money under the day cap.
#
# The container's only writable mount is build/evals. The container runs as the host user
# (`--user uid:gid`, verified to work with the Paketo image), so the directory stays 0755 and
# nothing in it belongs to another uid. Everything the container wrote is untrusted input:
# symlinks and non-regular files are refused, and the reports pass the capture scrubber (no
# secret, token, nonce, signature) before they are copied into docs/evals. Reports are picked
# up by name when the app writes latest.md / latest.json, otherwise the newest *.md and *.json
# at the top of build/evals are used.
#
# Test hook (scripts/capture-demo/test-scrub.sh): EVAL_RUN_CMD replaces the stack check and the
# docker run with the given command, so the publishing rules can be tested without a stack.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/capture-demo/scrub.sh
source scripts/capture-demo/scrub.sh

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.yml}"
out_dir="${EVAL_OUT_DIR:-build/evals}"
publish_dir="${EVAL_PUBLISH_DIR:-docs/evals}"

if [[ "${EVAL_ANSWERS:-}" == "1" ]]; then
  export SAIMAN_EVALS_ANSWERS_ENABLED=true
  ports=(8083 8081)
  echo "run-evals: answer tier ON (real LLM calls via seller-api /internal/v1/eval/questions, day-capped)"
else
  export SAIMAN_EVALS_ANSWERS_ENABLED=false
  ports=(8083)
fi

if [[ -z "${EVAL_RUN_CMD:-}" ]]; then
  scripts/ensure-secret-files.sh
  WAIT_FOR_HEALTH_TIMEOUT="${WAIT_FOR_HEALTH_TIMEOUT:-30}" scripts/wait-for-health.sh "${ports[@]}"
fi

mkdir -p "${out_dir}"
chmod 755 "${out_dir}"
# Everything goes, symlinks included (-delete never follows them): stale output can't be published.
find "${out_dir}" -mindepth 1 -delete

if [[ -n "${EVAL_RUN_CMD:-}" ]]; then
  bash -c "${EVAL_RUN_CMD}"
else
  GIT_SHA="$(git rev-parse --short HEAD)" docker compose -f "${COMPOSE_FILE}" --profile evals run --rm --no-deps --user "$(id -u):$(id -g)" evals
fi

# Refuse symlinks and anything that is not a plain file or directory before reading a byte.
if [[ -n "$(find "${out_dir}" -mindepth 1 \( -type l -o \( ! -type f ! -type d \) \) -print -quit)" ]]; then
  echo "run-evals: ${out_dir} contains a symlink or a non-regular file; nothing was published" >&2
  exit 1
fi

newest() { # newest <extension>
  find "${out_dir}" -maxdepth 1 -type f -name "*.$1" -printf '%T@ %p\n' | sort -rn | sed -n '1s/^[^ ]* //p'
}
report_md="${out_dir}/latest.md"
report_json="${out_dir}/latest.json"
[[ -f "${report_md}" ]] || report_md="$(newest md)"
[[ -f "${report_json}" ]] || report_json="$(newest json)"
if [[ -z "${report_md}" || -z "${report_json}" ]]; then
  echo "run-evals: the evals container wrote no *.md / *.json report into ${out_dir}" >&2
  exit 1
fi

for report in "${report_md}" "${report_json}"; do
  [[ -f "${report}" && ! -L "${report}" ]] || {
    echo "run-evals: ${report} is not a regular file; nothing was published" >&2
    exit 1
  }
  scrub_check "${report}" || {
    echo "run-evals: scrub check failed for ${report}; nothing was published" >&2
    exit 1
  }
done

stamp="$(date -u +%F)-$(git rev-parse --short HEAD)"
mkdir -p "${publish_dir}/runs"
cp "${report_md}" "${publish_dir}/latest.md"
cp "${report_json}" "${publish_dir}/latest.json"
cp "${report_json}" "${publish_dir}/runs/${stamp}.json"
echo "run-evals: published ${publish_dir}/latest.md, latest.json and runs/${stamp}.json"
