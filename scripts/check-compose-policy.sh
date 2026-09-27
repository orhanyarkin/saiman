#!/usr/bin/env bash
# Static policy checks on deploy/compose/docker-compose.yml, run in CI so a change to
# the compose file can't silently reintroduce an exposed port, an accidental registry
# pull of a dev image, or a reference to the third-party "saiman/" Docker Hub
# namespace (our images are ghcr.io/orhanyarkin/saiman-<svc>:dev).
#
# Resolves the config with `--no-interpolate --no-env-resolution` so it never reads a
# local .env (app services carry no env_file in M0 anyway; see docker-compose.yml).
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.yml}"

fail_check() {
  echo "check-compose-policy: FAIL: $*" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "check-compose-policy: required command '$1' not found on PATH" >&2
    exit 1
  }
}

require_command docker
require_command jq

config_json=$(docker compose -f "${COMPOSE_FILE}" --profile apps config --no-interpolate --no-env-resolution --format json)

violations=0

# 1. Every published port on every service must bind to 127.0.0.1 only (never 0.0.0.0
#    or an unset host_ip, which Docker treats as "all interfaces").
bad_ports=$(jq -r '
  .services
  | to_entries[]
  | .key as $svc
  | (.value.ports // [])[]
  | select(.host_ip != "127.0.0.1")
  | "\($svc): port \(.published // "?")->\(.target) has host_ip \"\(.host_ip // "<unset>")\" (must be 127.0.0.1)"
' <<<"${config_json}")
if [[ -n "${bad_ports}" ]]; then
  fail_check "port(s) not bound to 127.0.0.1:"
  echo "${bad_ports}" >&2
  violations=1
fi

# 2. Every service in the "apps" profile must set pull_policy: never, so compose can
#    never silently fall back to pulling an image with this tag from a registry
#    instead of using the one just built locally by `./gradlew bootBuildImage`.
bad_pull_policy=$(jq -r '
  .services
  | to_entries[]
  | select((.value.profiles // []) | index("apps"))
  | select(.value.pull_policy != "never")
  | "\(.key): pull_policy is \"\(.value.pull_policy // "<unset>")\" (must be \"never\")"
' <<<"${config_json}")
if [[ -n "${bad_pull_policy}" ]]; then
  fail_check "app service(s) without pull_policy: never:"
  echo "${bad_pull_policy}" >&2
  violations=1
fi

# 3. No service image may reference the "saiman/" Docker Hub namespace: that Hub user
#    belongs to a third party, not this project.
bad_images=$(jq -r '
  .services
  | to_entries[]
  | select(.value.image != null)
  | select(.value.image | test("^(docker\\.io/)?saiman/"))
  | "\(.key): image \"\(.value.image)\" matches the forbidden saiman/ namespace"
' <<<"${config_json}")
if [[ -n "${bad_images}" ]]; then
  fail_check "service(s) referencing the forbidden saiman/ image namespace:"
  echo "${bad_images}" >&2
  violations=1
fi

if [[ ${violations} -ne 0 ]]; then
  exit 1
fi

echo "check-compose-policy: PASS (ports bound to 127.0.0.1, app pull_policy: never, no saiman/ images)"
