# ADR-0010: RAG data source — the official MKK KAP data API (free tier), a frozen 2023 corpus

Status: Accepted (2026-09-29)

## Context
M2 needs public Turkish capital-markets disclosures with a source URL and retrieval time per chunk (CLAUDE.md rule 8). Options examined:
- **kap.org.tr's own web JSON API** (`/tr/api/disclosure/...`): live and works, but undocumented, has no published terms (`robots.txt` answers with a WAF block) and is reported to block datacenter IPs.
- **The official KAP data distribution API** published by MKK (`/api/vyk`, OpenAPI documented, 12 services). The free tier of the MKK API Portal is the documented **test environment** (`https://apigwdev.mkk.com.tr`): real historical KAP data for disclosure indexes **1091689–1231017** (roughly all of 2023; the last one is dated 29.12.2023), Basic authentication, throttled to **6 calls/minute**. Production (`generateToken`, API key, sender-IP whitelist) is for data-distribution companies and is not available to us. Verified 2026-09-29: the same index returns the identical record on public kap.org.tr.
- **News**: GDELT DOC 2.0 (metadata only, permissive licence) only covers a rolling three months, so 2026 headlines would not line up with a 2023 disclosure corpus; Anadolu Ajansı RSS forbids non-subscriber use.

## Decision
- The corpus is the **official MKK KAP API test environment**: a static, reproducible snapshot "as of 2023-12-29" (`corpusWatermark`). Freshness is a stated limitation of the demo, not a defect.
- Only listed-company disclosures of class **ODA** (material events) and **DG** (other, e.g. corporate-governance forms) for a configurable list of ~20 BIST tickers. **FR** financial reports are skipped: their HTML is a form shell and the content is in PDF attachments; attachments are not fetched or redistributed.
- **No news source in M2.** The unofficial KAP web API is not used.
- Encoded rules:
  - HTTP Basic credential from a secret file (ADR-0009), never logged; requests limited to **5 calls/minute** (Resilience4j `RateLimiter`, headroom under 6/min) and honouring `429`/`Retry-After`; no redirects; timeouts; circuit breaker.
  - `disclosures` filtered by `companyId` is **windowed** (it scans a bounded, variable index window): page with cursor = highest returned index + 1, advance by a fixed step on empty pages, stop past `lastDisclosureIndex`.
  - `htmlMessages[].tr` is base64 XHTML whose text is UTF-8 despite its `ISO-8859-9` declaration; strip `<style>`/`<script>` before text extraction.
  - **`/blockedDisclosures` is honoured** (personal-data removals): blocked disclosures are never indexed and already-indexed chunks are deleted.
  - Citations link to `https://www.kap.org.tr/tr/Bildirim/<disclosureIndex>`; served text is excerpts with a link back, never a bulk republication. No KAP text is committed to git (tests use synthetic fixtures with the same shape).

## Consequences
+ Official, documented, legally clean; a static corpus makes idempotency tests and the M6 eval golden set reproducible.
+ Incremental cursor by disclosure index fits the idempotent pipeline.
− Data is from 2023; the demo answers questions about 2023 disclosures. Live data would need MKK approval (ask kapdestek@mkk.com.tr) or the unofficial API (rejected here).
− 6 calls/minute makes the backfill a long-running, resumable job (hours), never part of CI.
Revisit if MKK grants a live tier, or if the corpus must be fresh.
