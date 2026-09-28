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
