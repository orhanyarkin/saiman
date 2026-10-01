---
name: m4-close-audit
description: M4 milestone-end audit 2026-10-01 (m4-ledger @ 2a0bfbe) - NUL eventId stalls ledger partition forever, recon Lease abort runs after close (fix ineffective), with-tx forged flood still starves, LOCAL release verified sound
metadata:
  type: project
---
Read-only M4 close audit (2026-10-01, branch m4-ledger @ 2a0bfbe). No probes; traced by reading. Cut at 40 turns.

No Critical/High. Findings:
- Medium: EventMetadata.eventId only checks 1-64 chars; a forged payments.* record with "\u0000" in eventId passes the strict parser, then the inbox INSERT fails in Postgres (22021, text cannot hold NUL) -> DataIntegrityViolation is not Malformed/Conflicting -> infinite back-off -> single-partition topic stalls forever. Any deterministic DB error is misclassified as transient.
- Medium: ReconciliationService.Lease.close uses try(connection){unlock} catch{abort}: try-with-resources closes (returns to pool) BEFORE the catch, so abort hits a closed proxy; the session advisory lock can stay on a pooled connection. HeldPaymentResolver.releaseLease does it right.
- Low: fair batching only splits with-tx vs without-tx; forged seller SETTLED with a bogus tx hash lands in the reserved half; NULLS FIRST lets fresh forgeries delay re-checks of old real payments. THREAT_MODEL "cannot starve" is overstated.
- Low: Redpanda dev-container may enable pandaproxy (HTTP produce, :8082) + schema registry on the compose network (not verified).
- Low: backfill throws on one bad row -> orchestrator startup fails; resolver eth_call by number not EIP-1898 hash; no sanity check of safe.timestamp vs wall clock.
Verified sound: LOCAL release (markSigned requires RESERVED, SIGNED+nonce+AUTHORIZED commit together before send), resolver only releases on authorizationState=false at safe past validBefore, commit failure -> HELD, producers can't fail on bounded records with honest inputs (fixed paths, USDC, <=max per request), seller recorder bounded (~<10 s worst, inside 65 s buyer floor), no float money, mainnet only in negative fixtures, compose RPC regex pins host.
**How to apply:** at M5/M6 re-check the eventId charset bound + DataIntegrity classification and the Lease close ordering landed; broker auth in M6 closes the forged-input class.
