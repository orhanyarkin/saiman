---
name: m5-close-audit
description: M5 (dashboard) milestone-end audit 2026-10-05 on m5-dashboard @ 79628fa - no Crit/High/Med; nginx SSE regex location is dead (^~ /api/ wins), proxied SSE buffered; ADR-0022 claim wrong
metadata:
  type: project
---
Read-only audit 2026-10-05, diff 04e1d23..79628fa, live probes against nginx :8088 (new images).

No Critical/High/Medium. Findings:
- Low: deploy/compose/nginx.conf SSE `location ~ ^/api/v1/runs/[^/]+/events$` never matches: the longest prefix `location ^~ /api/` disables regex checks. CONFIRMED live: events JSON comes back gzip-encoded (the SSE block has gzip off). So SSE goes through proxy_buffering on + 60 s read timeout; ADR-0022 "SSE locations without buffering" is false; check-nginx-conf.sh does not catch it. Fix: nest the regex inside `location ^~ /api/` (or drop ^~) and/or emit `X-Accel-Buffering: no` from RunEventController.
- Info: server_tokens off only in the localhost server -> default-server 400 page shows nginx/1.30.5; direct :4318 collector has no Host check (pre-existing); CI gen:api/lighthouse steps self-skip if scripts are renamed; parseRunEvent returns the raw object (comment says extra fields are not copied) and does not compare runId with the subscribed run.
Verified live OK: 127.0.0.1 binds only; Host evil -> 444; absolute-form request-line host vs Host header fails closed (backend 400); TRACE 405; preflight 200 without ACAO (Spring), OTLP preflight 405, text/plain OTLP 415; no CORS headers; POST without CSRF -> 403 through nginx; 64k nginx body cap; `..;`, `//`, `..` -> backend 400; %2e/%2f traversal normalised by nginx to static; /v3/api-docs 404; Tomcat 400 pages echo nothing; dist has no fixtures/keys; CSP/nosniff/XFO on all static responses.
SPA OK: no innerHTML sinks; links only via safeKapUrl/basescan builders with rel=noopener; detail capped 300 chars; mutations only via apiPost with both headers; no storage use; approvals POST id+choice only.
Prior fixes hold: sale_verified needs chain_tx_hash; BoundedReads statement_timeout in both services.
**How to apply:** at M6 check SSE through nginx is unbuffered with a test, and re-check nginx precedence whenever a location is added.
