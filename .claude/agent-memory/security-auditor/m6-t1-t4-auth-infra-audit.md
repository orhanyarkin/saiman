---
name: m6-t1-t4-auth-infra-audit
description: M6 T1 api-security + T4 infra audit (2026-10-05) - superuser literal pw, owner pw in app containers, auth.enabled hatch, evals symlink, scrub argv
metadata:
  type: project
---
Per-task audit of ed8bfdc (libs/api-security) and b07e38f/7074a90 (infra) on m6-hardening. No Crit/High.

- Medium: postgres superuser `saiman/saiman` literal (compose postgres + db-init env); every compose peer = superuser, defeats ADR-0024. Fix: secret file + POSTGRES_PASSWORD_FILE + ALTER ROLE in db-init; policy PASSWORD rule on all services.
- Medium: `<svc>_owner` password mounted in the running app (spring.flyway.password) -> RCE in ledger = owner = DISABLE TRIGGER / rewrite postings. ADR-0024 "ledger can't rewrite postings" holds only vs SQL injection. Fix: per-service migrate one-shot.
- Medium: `saiman.auth.enabled` - @ConditionalOnProperty string match vs boolean binding (0/off/no => neither config, no warning); no second-key guard; compose policy only blocks the uppercase env name (lowercase dotted env key or `command:` args bypass).
- Medium: run-evals.sh 777 dir + `find -type f -delete` keeps symlinks + `[[ -f ]]`/cp follow them -> secrets/* copied into docs/evals (committed).
- Low: scrub.sh greps secret content via argv; bootstrap pw in statement text (log_statement=ddl on RDS); policy bypasses (dotted env keys, command/entrypoint, URL ?password=, RO bind of repo root/build); dir 0700 only warned.
- Sound: isEqual over all digests, regex before hash, fixed 401/403, header-only resolver, role/SERVICE_ separation, %I/regprocedure quoting, PG17 public CREATE revoked, nginx has no seller/ingest upstream.
**How to apply:** at T2/T3 check services don't add a permitAll chain for auth-off and use hasAuthority(SERVICE_<caller>) not hasRole(SERVICE); at M6 close verify superuser secret + migrate split or documented residual.
