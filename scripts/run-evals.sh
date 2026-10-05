#!/usr/bin/env bash
# `make eval` (ADR-0025): runs the `evals` compose one-shot (profile `evals`) on the compose
# network, then publishes its reports from build/evals into docs/evals/:
#   latest.md, latest.json            the newest report
#   runs/<date>-<sha>.json            the same JSON, kept per run (date = UTC, sha = short git HEAD)
# EVAL_ANSWERS=1 enables the answer tier (SAIMAN_EVALS_ANSWERS_ENABLED=true): it calls the
# seller-api's internal eval endpoint, which spends LLM money under the day cap.
#
# The container's only writable mount is build/evals (the container user's uid differs from the
# host's, so the directory is world-writable; it holds reports only, no secrets). Reports are
# picked up by name when the app writes latest.md / latest.json, otherwise the newest *.md and
# *.json at the top of build/evals are used.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

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

scripts/ensure-secret-files.sh
WAIT_FOR_HEALTH_TIMEOUT="${WAIT_FOR_HEALTH_TIMEOUT:-30}" scripts/wait-for-health.sh "${ports[@]}"

mkdir -p "${out_dir}"
chmod 777 "${out_dir}"
find "${out_dir}" -mindepth 1 -maxdepth 1 -type f -delete

docker compose -f "${COMPOSE_FILE}" --profile evals run --rm --no-deps evals

newest() { # newest <glob-suffix>
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

stamp="$(date -u +%F)-$(git rev-parse --short HEAD)"
mkdir -p "${publish_dir}/runs"
cp "${report_md}" "${publish_dir}/latest.md"
cp "${report_json}" "${publish_dir}/latest.json"
cp "${report_json}" "${publish_dir}/runs/${stamp}.json"
echo "run-evals: published ${publish_dir}/latest.md, latest.json and runs/${stamp}.json"
