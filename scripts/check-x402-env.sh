#!/usr/bin/env bash
# Fails fast, before any image build, if X402_SELLER_PAYTO_ADDRESS is missing or
# malformed. seller-api's @RequiresPayment endpoint refuses to start without a valid
# payout address (ADR-0008, ADR-0009), so `make up` checks it before spending time on
# `./gradlew bootBuildImage`. `make infra-up` does not call this script: it starts only
# postgres/kafka/redis/otel-collector/jaeger, none of which need it.
#
# `make` exports the value from the shell or, failing that, from the repo-root .env
# (scripts/read-public-env.sh). Run directly, the script needs it exported: docker compose does not read the
# repo-root .env (only `.env` files next to a compose file are auto-loaded, and this
# repo intentionally has none there — see docker-compose.yml and ADR-0009).
#
# Never prints the offending value: this script only ever reports its length or a
# generic shape (e.g. "looks like a private key"), never the string itself, in case a
# private key was pasted where an address was expected.
set -euo pipefail

# Base Sepolia test USDC contract (TestnetAssets, docs/design/m1-x402.md) — not a valid
# payTo: paying the token contract itself would burn the funds.
USDC_CONTRACT_LOWER="0x036cbd53842c5426634e7929541ec2318f3dcf7e"
ZERO_ADDRESS_LOWER="0x0000000000000000000000000000000000000000"

address="${X402_SELLER_PAYTO_ADDRESS:-}"

if [[ -z "${address}" ]]; then
  cat >&2 <<'EOF'
check-x402-env: X402_SELLER_PAYTO_ADDRESS is not set.

seller-api's paid endpoint needs a public payout address before it can start (ADR-0008,
ADR-0009); `make up` refuses to build images without one.

Create a throwaway testnet address, e.g. with Foundry's `cast wallet new`, or any wallet
tool of your choice. Keep only the printed "Address" (0x followed by 40 hex characters)
— never the private key, and never commit either. Then either put it in the repo-root
.env (`make` reads only this one variable from there) or export it in this shell:
  X402_SELLER_PAYTO_ADDRESS=0x...        # in .env
  export X402_SELLER_PAYTO_ADDRESS=0x... # or in the shell (wins over .env)

`make infra-up` does not need this: it starts only postgres/kafka/redis/otel-collector/jaeger.
EOF
  exit 1
fi

# A 32-byte hex string (0x + 64 hex chars) is the shape of a private key, not an
# address (0x + 40 hex chars) — most likely someone pasted the wrong line from their
# wallet tool's output. Never print the value itself.
if [[ "${address}" =~ ^0x[0-9a-fA-F]{64}$ ]]; then
  echo "check-x402-env: X402_SELLER_PAYTO_ADDRESS (${#address} characters) looks like a private key, not an address." >&2
  echo "check-x402-env: discard that wallet (its key is now in your shell history/environment) and use the tool's \"Address\" line instead, e.g. \`cast wallet new\`." >&2
  exit 1
fi

if [[ ! "${address}" =~ ^0x[0-9a-fA-F]{40}$ ]]; then
  echo "check-x402-env: X402_SELLER_PAYTO_ADDRESS (${#address} characters) is not a valid address (must be 0x followed by 40 hex characters)." >&2
  exit 1
fi

address_lower="${address,,}"

if [[ "${address_lower}" == "${ZERO_ADDRESS_LOWER}" ]]; then
  echo "check-x402-env: X402_SELLER_PAYTO_ADDRESS is the zero address, which cannot receive payments." >&2
  exit 1
fi

if [[ "${address_lower}" == "${USDC_CONTRACT_LOWER}" ]]; then
  echo "check-x402-env: X402_SELLER_PAYTO_ADDRESS is the Base Sepolia test USDC contract address, not a wallet — paying it would burn the funds." >&2
  exit 1
fi

echo "check-x402-env: X402_SELLER_PAYTO_ADDRESS OK"
