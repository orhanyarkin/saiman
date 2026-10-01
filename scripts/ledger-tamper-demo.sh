#!/usr/bin/env bash
# LOCAL COMPOSE ONLY. Deliberately CORRUPTS the local ledger to demonstrate that reconciliation
# catches tampering (docs/design/m4-ledger.md "Live plan", ADR-0018).
#
# Inside one transaction it sets `session_replication_role = replica`, which disables the
# ledger's immutability and balance triggers (the very mechanism that normally forbids editing a
# posting), then raises both postings of the most recent SALE entry by 5000 atomic units
# (0.005 USDC). The entry stays balanced, but it no longer matches the payment projection or the
# chain, so the next `make recon-run recon-report` must report a MISMATCH and book the
# difference to suspense.
#
# It refuses to run unless the postgres container belongs to the local compose project
# `saiman`; never point it at anything else. It prints only amounts and ids, never secrets.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.yml}"
compose=(docker compose -f "${COMPOSE_FILE}")

cid="$("${compose[@]}" ps -q postgres 2>/dev/null || true)"
if [[ -z "${cid}" ]]; then
  echo "ledger-tamper-demo: the local compose postgres is not running (make infra-up / make up)." >&2
  exit 1
fi
project="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "${cid}" 2>/dev/null || true)"
if [[ "${project}" != "saiman" ]]; then
  echo "ledger-tamper-demo: refusing: the postgres container is not the local compose project 'saiman' (got '${project}')." >&2
  exit 1
fi

echo "ledger-tamper-demo: tampering with the LOCAL compose ledger (this bypasses the immutability triggers on purpose)."
"${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U saiman -d saiman <<'SQL'
BEGIN;
SET LOCAL session_replication_role = replica;
WITH target AS (
  SELECT id FROM ledger.journal_entry WHERE kind = 'SALE' ORDER BY recorded_at DESC, id DESC LIMIT 1
)
UPDATE ledger.posting p
   SET amount_atomic = p.amount_atomic + 5000
  FROM target
 WHERE p.entry_id = target.id
RETURNING p.entry_id, p.side, p.amount_atomic - 5000 AS was_atomic, p.amount_atomic AS now_atomic;
COMMIT;
SQL
echo "ledger-tamper-demo: done (no rows above = no SALE entry yet; run a paid research first)."
echo "Next: make recon-run recon-report"
