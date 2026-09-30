# ADR-0009: Secrets and wallet keys per service

Status: Accepted (2026-09-28)

## Context
M0 left open how secrets reach services and which containers may hold a wallet key. x402 has two roles: the seller, which needs a public `payTo` address, and the buyer, which signs EIP-3009 authorizations with a private key. The facilitator settles on chain and pays gas, so the seller never signs anything.

## Decision
- **seller-api holds no key.** It gets `X402_SELLER_PAYTO_ADDRESS` (a public address) as a plain environment variable. The variable is required: `make up` fails fast with a clear message if it is missing or not a 0x-prefixed 20-byte hex address.
- **The buyer key reaches only the buyer.** In M1 that is the console buyer (`samples/console-buyer`), which reads `X402_BUYER_PRIVATE_KEY` or a file under `secrets/` (git-ignored, mode 0600). From M3 it goes only to the orchestrator container, through compose `secrets:` mounted at `/run/secrets/` and read with `spring.config.import=optional:configtree:/run/secrets/`.
- Compose `secrets:` wiring is deferred to M3: a secret file that doesn't exist would break `make up` on a clean clone, and no container needs a key before then.
- Keys are never logged: properties records that hold them override `toString()`, and the signer exposes only its address.
- `x402-testnet-check` runs locally only in M1; no buyer key is stored in GitHub secrets. Revisit in M6.

## Alternatives
- One `.env` shared by every container: simplest, but gives every service every secret.
- A secrets manager (AWS Secrets Manager / SSM): right for the AWS deployment (ADR-0004), decided with the Terraform work in M6.

## Consequences
+ A compromised seller-api cannot move funds.
+ The configtree pattern is the same one used for Kubernetes secrets later.
− Paketo images run as a non-root user; mounted secret files must be readable by that user (check in M3).
Revisit if: a service other than the orchestrator needs to sign.

## Amendment (2026-09-29, M2): provider and data-source credentials
- `ingest` holds the read-only **MKK API credential** (`mkk_credentials`, ADR-0010) and an **OpenAI API key**; `seller-api` holds the OpenAI key too (it answers questions through the model router, ADR-0011). Both are real, cost- or quota-bearing secrets, so they follow the buyer-key pattern: files under the git-ignored `secrets/` (mode 0600), mounted with compose `secrets:` only into the containers that need them, read through `spring.config.import=optional:configtree:…`. Never an environment variable, never in `.env` for containers, never logged. A `make`-time read of `.env` stays limited to the one public payout address.
- `scripts/check-compose-policy.sh` allows exactly: `ingest` → `mkk_credentials`, `openai_api_key`; `seller-api` → `openai_api_key`; `orchestrator` → the buyer key (from M3). Any other service or secret fails the policy.
- Spend containment does not rely on the key alone: the OpenAI project has a hard provider-side limit, and the router enforces a daily USD cap in code.
- Mounted secret files are 0644 inside the 0700 `secrets/` directory because the container user's uid differs from the host user's (compose file secrets keep host owner and mode); the directory mode is the protection. `buyer.key`, which is not mounted, stays 0600.

## Amendment (2026-09-30, M3): orchestrator secrets and the plaintext exception
- `orchestrator` holds the buyer key (`secrets/x402_buyer_private_key`, mounted as `x402.client.private-key` through the compose long syntax `target:`) and an OpenAI key. The compose policy allows exactly those two for that service; the human creates the key file (0644 inside the 0700 directory, as above).
- Inside compose the orchestrator reaches `http://seller-api:8081` over plaintext. The starter refuses to sign over plaintext except for loopback and for hosts named **exactly** in `x402.client.allowed-plaintext-hosts` (no wildcard or suffix); the list is empty by default and startup fails if it is non-empty on any network other than the Base Sepolia testnet.

