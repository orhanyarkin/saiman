---
name: m4-close-fix-reaudit
description: M4 close-fix re-review 2026-10-01 (m4-ledger @ d2efd87) - RPC-failure paths keep HELD, min(safe,now) bound sound for honest RPC, residual: ts not bound to block number, ledger lacks clock bound, unmapped SQLSTATE -> quarantine
metadata:
  type: project
---
One-time re-review of the M4 close fixes (cdae10b, ae2b93c, 446f3f1, 93fe893). No Critical/High.

Verified sound:
- Resolver: every RPC failure leaves HELD. ChainUnavailable (IO/429/5xx/-32005/-32016/other JSON-RPC errors/malformed/CB open/limiter) caught; wrong chain id = IllegalStateException escapes resolveLocked before any claim (no state change, no metric); per-intent RuntimeException -> guarded "error"; authorizationState returns false only for exact 32-byte zero. LOCAL path needs auth_nonce IS NULL in claim AND update, no RPC involved.
- Clock: release iff vB < min(safe.ts, now) and state=false at safe.number; EIP-3009 usable iff block.ts < vB, so 1 s conservative margin; local clock can only delay. Ledger: S > vB strict everywhere, due SQL vB < safeTs - grace matches booksOpen.
- Lease close now unlock -> abort-if-not-unlocked -> close (fixed).
Residual (Low/Info): number<->timestamp of safe block not bound (lying RPC can fake ts up to now+60; OP-stack ts = genesis + 2*number would pin it); ledger has no now/60 s bound (spurious SETTLED_BUT_UNUSED, self-correcting); unmapped SQLSTATE (UncategorizedSQLException, 42xxx perms) -> DETERMINISTIC -> real events DLT'd at once; commit-time plain PSQLException in TransactionSystemException -> UNKNOWN (1 h then DLT); no CHECK tying auth_nonce/payer/valid_before.
**How to apply:** at M6 (broker auth, RPC hardening) re-check the genesis-timestamp pin and SQLSTATE classification.
