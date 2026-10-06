#!/usr/bin/env bash
# Interleaved A/B experiment on the client's EIP-3009 `validAfter` back-dating (x402.client.clock-skew-seconds,
# docs/ops/facilitator-settle-failures.md, H5): arm A = 600 s (the default), arm B = 30 s. Runs the
# console buyer against the local seller-api's disclosure summary (0.01 test USDC per settlement, a
# RAG call per request). Units are PAIRS of buys a few seconds apart (the failures seen so far were
# mostly the second payment of a run), the arm order is randomised in blocks of two units, and units are
# spaced out to stay under seller-api's per-payer limit (30 runs per hour).
#
# Spending is capped: BUY_CEILING buys (0.01 USDC each) and a ceiling on the LLM day counter. The
# script stops, writes what it has and exits non-zero when either is reached. Nothing is retried:
# a buy that fails is recorded, never repeated (the seller never retries /settle either).
#
# Output: a TSV in build/experiments/ (one row per buy: unit, arm, skew, start, end, exit code, HTTP status,
# tx hash or "-"). Settlement outcomes and facilitator messages are read afterwards from the seller's
# own records (seller_api.settlement) and its WARN lines; this script never prints a key or a token.
#
# Usage: scripts/ops/x402-skew-experiment.sh [units-per-arm=10]
# Needs `make up`, `make x402-publish-local`, the console-buyer jar (`./gradlew -p
# libs/x402-spring-boot-starter/samples/console-buyer bootJar`) and secrets/buyer.key (funded).
set -euo pipefail

units_per_arm="${1:-10}"
pair_gap="${PAIR_GAP:-8}"          # seconds between the two buys of a unit
unit_gap="${UNIT_GAP:-270}"        # seconds between units (30 buys/hour is the seller's per-payer limit)
buy_ceiling="${BUY_CEILING:-40}"   # at most this many buys = 0.40 USDC
llm_ceiling_micros="${LLM_CEILING_USD_MICROS:?set LLM_CEILING_USD_MICROS to the absolute llmDay.spentUsdMicros at which to stop}"
url="${EXPERIMENT_URL:-http://localhost:8081/v1/disclosures/THYAO/summary}"
jar="libs/x402-spring-boot-starter/samples/console-buyer/build/libs/x402-console-buyer-0.1.0-SNAPSHOT.jar"
out="build/experiments/skew-$(date -u +%Y%m%dT%H%M%SZ).tsv"

[[ -f "${jar}" ]] || { echo "experiment: build the console-buyer jar first" >&2; exit 1; }
mkdir -p build/experiments

pay_to="$(scripts/curl-auth.sh reader -s http://localhost:8082/api/v1/ledger/revenue | jq -r '.items[0].payTo')"
[[ "${pay_to}" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo "experiment: could not read the seller payTo" >&2; exit 1; }

# Randomised blocks: every two consecutive units hold one of each arm in a seeded random order, so a
# time-dependent facilitator state cannot favour one arm and the sequence is reproducible.
arms="$(awk -v n="${units_per_arm}" 'BEGIN { srand(20261005); for (i = 0; i < n; i++) if (rand() < 0.5) print "A\nB"; else print "B\nA" }')"

printf 'unit\tpos\tarm\tskew\tstart_utc\tend_utc\texit\thttp\ttx\n' >"${out}"
echo "experiment: ${units_per_arm} units per arm, output ${out}"

buys=0
unit=0
while read -r arm; do
  unit=$((unit + 1))
  skew=600
  [[ "${arm}" == "B" ]] && skew=30
  for pos in 1 2; do
    if ((buys >= buy_ceiling)); then
      echo "experiment: buy ceiling ${buy_ceiling} reached, stopping" >&2
      exit 3
    fi
    spent="$(scripts/curl-auth.sh reader -s http://localhost:8080/api/v1/spend | jq -r '.llmDay.spentUsdMicros')"
    if ((spent >= llm_ceiling_micros)); then
      echo "experiment: LLM ceiling reached (${spent} >= ${llm_ceiling_micros} micro-USD), stopping" >&2
      exit 4
    fi
    start="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    rc=0
    log="$(X402_CLIENT_ALLOWED_PAY_TO="${pay_to}" X402_CLIENT_CLOCK_SKEW_SECONDS="${skew}" \
      java -jar "${jar}" buy --url="${url}" 2>&1)" || rc=$?
    end="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    http="$(grep -m1 '^status: ' <<<"${log}" | sed 's/^status: //' || true)"
    tx="$(grep -m1 '^tx hash: ' <<<"${log}" | sed 's/^tx hash: //' || true)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${unit}" "${pos}" "${arm}" "${skew}" "${start}" "${end}" \
      "${rc}" "${http:--}" "${tx:--}" >>"${out}"
    buys=$((buys + 1))
    echo "unit ${unit}.${pos} arm ${arm} skew ${skew}: exit ${rc} http ${http:--}"
    ((pos == 1)) && sleep "${pair_gap}"
  done
  sleep "${unit_gap}"
done <<<"${arms}"
echo "experiment: finished, ${buys} buys, ${out}"
