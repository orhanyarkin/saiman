---
name: m4-chain-eventing-facts
description: Verified facts behind the M4 design (2026-10-01): Base Sepolia RPC limits, EIP-3009 state, Spring Modulith registry scope, PBT library landscape
metadata:
  type: reference
---

- Public Base Sepolia RPC `https://sepolia.base.org`: rate-limited, "testing only"; `eth_getLogs` capped at 1000 blocks per call. `safe` lags head by about 1 min, `finalized` by about 19 min.
- EIP-3009 `authorizationState(from, nonce)` becomes true on use AND on `cancelAuthorization`; "used" without a Transfer is not proof of a settlement with a known tx.
- Spring Modulith 2.1.1 is built against Boot 4.1.1 / Spring Kafka 4.1.1. Its registry by default tracks every `@TransactionalEventListener`; narrow with `spring.modulith.events.registry-trigger-annotation`. Externalization is async and not ordered; `republish-outstanding-events-on-restart` resends incomplete ones.
- Testcontainers 2.0.5 has `testcontainers-redpanda`; Boot 4.1 supports a Redpanda `@ServiceConnection`.
- Property testing: jqwik has an Anti-AI Usage Clause since 1.10 and is in maintenance mode; junit-quickcheck is JUnit 4 (last release 2021); QuickTheories unmaintained. Saiman uses seeded JUnit 5 generators (ADR-0019).
