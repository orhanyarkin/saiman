#!/usr/bin/env bash
# Keeps the demo-lite third-party images in step with docker-compose (audit L4): kafka, redis, otel and nginx
# must have the same tag in deploy/compose/docker-compose.yml and in demo-lite/containers.tf (`local.images`),
# and the Postgres client image must have the same major version as compose's pgvector image. Offline.
# Usage: check-mirror-versions.sh [<docker-compose.yml> [<containers.tf>]]    Exit 0 equal, 1 drift, 2 usage.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose="${1:-${here}/../../../compose/docker-compose.yml}"
tf="${2:-${here}/../demo-lite/containers.tf}"
[[ -f "${compose}" && -f "${tf}" ]] || {
  echo "usage: check-mirror-versions.sh [<docker-compose.yml> [<containers.tf>]]" >&2
  exit 2
}

# compose_tag <image repo path as written in compose, e.g. apache/kafka>
compose_tag() {
  sed -nE "s|^[[:space:]]*image:[[:space:]]*$1:([^@[:space:]]+).*|\1|p" "${compose}" | head -n 1
}
# tf_tag <name in local.images>
tf_tag() {
  local ref
  ref="$(sed -nE "s|^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"([^\"@]+)(@sha256:[0-9a-f]{64})?\".*|\1|p" "${tf}" | head -n 1)"
  echo "${ref##*:}"
}

rc=0
check() { # <label> <compose repo> <tf name>
  local want have
  want="$(compose_tag "$2")"
  have="$(tf_tag "$3")"
  if [[ -z "${want}" || -z "${have}" ]]; then
    echo "check-mirror-versions: cannot find $1 (compose='${want}', containers.tf='${have}')" >&2
    rc=1
  elif [[ "${want}" != "${have}" ]]; then
    echo "check-mirror-versions: DRIFT $1: compose ${want} vs containers.tf ${have}" >&2
    rc=1
  else
    echo "ok $1 ${have}"
  fi
}
check kafka apache/kafka kafka
check redis redis redis
check otel otel/opentelemetry-collector otel
check nginx nginx nginx

pg_compose="$(sed -nE 's|^[[:space:]]*image:[[:space:]]*pgvector/pgvector:[0-9.]+-pg([0-9]+).*|\1|p' "${compose}" | head -n 1)"
pg_tf="$(tf_tag postgres | sed -E 's/^([0-9]+)\..*/\1/')"
if [[ -n "${pg_compose}" && "${pg_compose}" == "${pg_tf}" ]]; then
  echo "ok postgres major ${pg_tf}"
else
  echo "check-mirror-versions: DRIFT postgres major: compose pg${pg_compose} vs containers.tf ${pg_tf}" >&2
  rc=1
fi
exit "${rc}"
