#!/usr/bin/env bash
# Stops init tasks that demo-up started with `ecs run-task` (--started-by demo-up) and are still active, then waits
# briefly until none is left. Terraform does not know them (they are not part of the state), and ECS refuses to delete
# a cluster that still has active tasks, so a stuck init task (a cancelled demo-up, or the essential `corpus-restore` container that
# never started) would block `terraform destroy`. Service-managed tasks are not touched: Terraform deletes the service.
#
# Usage: stop-init-tasks.sh --region R [--cluster saiman-demo] [--timeout-seconds 180]
# Exit 0 nothing (left) to stop, 1 tasks still active after the timeout, 2 usage error.
set -euo pipefail

usage() {
  echo "usage: stop-init-tasks.sh --region R [--cluster saiman-demo] [--timeout-seconds N]" >&2
  exit 2
}
region="" cluster="saiman-demo" timeout=180
while [[ $# -gt 0 ]]; do
  [[ $# -ge 2 ]] || usage
  case "$1" in
    --region) region="$2" ;;
    --cluster) cluster="$2" ;;
    --timeout-seconds) timeout="$2" ;;
    *) usage ;;
  esac
  shift 2
done
[[ "${region}" =~ ^[a-z]{2}-[a-z]+-[0-9]$ && "${cluster}" =~ ^saiman-demo[a-z0-9-]*$ && "${timeout}" =~ ^[0-9]+$ ]] || usage

export AWS_PAGER=""
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-stop-init.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

# Prints the ARNs of the active init tasks; a cluster that does not exist (never created, or already deleted) has none.
list_tasks() {
  if ! aws --region "${region}" --output json ecs list-tasks --cluster "${cluster}" --started-by demo-up >"${work}/tasks.json" 2>"${work}/err.txt"; then
    if grep -q 'ClusterNotFoundException' "${work}/err.txt"; then
      echo '{}' >"${work}/tasks.json"
    else
      cat "${work}/err.txt" >&2
      return 1
    fi
  fi
  jq -r '.taskArns[]?' "${work}/tasks.json"
}

deadline=$((SECONDS + timeout))
stopped=0
while true; do
  mapfile -t tasks < <(list_tasks)
  if [[ ${#tasks[@]} -eq 0 ]]; then
    if [[ "${stopped}" -gt 0 ]]; then echo "stop-init-tasks: ${stopped} init task(s) stopped"; else echo "stop-init-tasks: no init task is active"; fi
    exit 0
  fi
  for t in "${tasks[@]}"; do
    aws --region "${region}" --output json ecs stop-task --cluster "${cluster}" --task "${t}" --reason "demo-destroy: stopping the init task before the cluster is deleted" >/dev/null
    stopped=$((stopped + 1))
  done
  if [[ "${SECONDS}" -ge "${deadline}" ]]; then
    echo "::error::init task(s) still active after ${timeout}s: ${tasks[*]##*/}" >&2
    exit 1
  fi
  sleep 3
done
