# UI refresh (post-M6 presentation pass)

Goal: bring `web/` to a visual standard that holds up on LinkedIn and in the README without
weakening a fintech dashboard's two real jobs, trust and readability. This is a presentation pass,
not a feature milestone; PLAN.md has no milestone for it.

The taste rules, design tokens, motion grammar, anti-slop list and the 14 point visual self-critique
checklist live outside this repository in `~/.claude/skills/ui-taste/SKILL.md`, with reference
screenshots in `~/.claude/skills/ui-taste/references/`. Every PR below is graded against that
checklist. Whoever picks this up must read that file first.

## Decisions already made

| Decision             | Value                                                                    | Note                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| -------------------- | ------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Accent               | **Ink** (monochrome CTA)                                                 | `--primary: oklch(0.265 0.020 255)`, light theme. Colour then means status and nothing else.                                                                                                                                                                                                                                                                                                                                                                                                                      |
| Focus ring           | `--info` blue, not the primary                                           | With an ink primary the ring must stay a distinct hue or focus stops reading as focus. 5.42:1 on surface.                                                                                                                                                                                                                                                                                                                                                                                                         |
| UI typeface          | Inter Variable, `@fontsource-variable/inter@5.3.0`, OFL-1.1, self-hosted | latin 48 KB, latin-ext 85 KB. Turkish content pulls latin-ext.                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| Display face         | Instrument Serif, `@fontsource/instrument-serif@5.3.0`, OFL-1.1          | Home `h1` only, loaded on that route only. latin 21 KB.                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| Home `h1` language   | English                                                                  | So the display face ships **latin only**. Any Turkish in the `h1` would pull latin-ext (+12 KB) and must be a deliberate decision.                                                                                                                                                                                                                                                                                                                                                                                |
| Dark mode            | Tokens stored, not shipped                                               | The dark block goes into `index.css` as the skill shows. No toggle, no dark screenshots, light only this phase.                                                                                                                                                                                                                                                                                                                                                                                                   |
| Motion library       | None by default                                                          | CSS plus the existing `tw-animate-css`. `motion` (54.6 KB gzip) is added only if a specific interaction is shown to be impossible in CSS, and then only after asking.                                                                                                                                                                                                                                                                                                                                             |
| Em dashes in UI copy | Removed                                                                  | Accepted from the skill's anti-slop list. Applies to interface strings only, not to documentation.                                                                                                                                                                                                                                                                                                                                                                                                                |
| Date and time format | `20 Oct 2026, 17:42 UTC`                                                 | en-GB, UTC, one shared helper beside `formatSnapshotDate` in `web/src/lib/corpus.ts`, which already formats `29 Dec 2023` the same way. Numeric formats are ambiguous (`10/5/2026` in the US shots, `5.10.2026` in a Turkish browser) and `toLocaleString()` follows the viewer's zone. The spend day, the replay banner date and the chain are all UTC already. The full ISO instant stays in the `title` and `dateTime` attributes. If local time with an explicit zone label is preferred, say so before PR 2. |
| Balances             | Shown on the account's normal side                                       | `Dr`/`Cr` instead of a signed `debit - credit`: revenue `-0.595` becomes `0.595 Cr`. See PR 5 for the one account where this is still not enough.                                                                                                                                                                                                                                                                                                                                                                 |

## Constraints

- No AWS calls, no SSM, no `demo-up` / `demo-down`, no `terraform plan` or `apply`. Terraform and
  compose **source changes** are in scope; running them against a cloud is not.
- Nothing under `secrets/` is read by any script written here, not even to print a count. Where a
  token is needed, the path is handed to Playwright and Playwright reads the file.
- Testnet only, light theme only, no new runtime dependency outside the table above.

## Environment banner

Today the banner is a hardcoded string in `web/src/routes/__root.tsx`:
`Testnet only - local demo. No real money moves.` In live mode the app has no environment signal at
all; `GET /api/v1/ping` returns `service`, `dbTime` and `traceId`, and the `environment` field only
exists inside a replay capture. So a deployed demo asserts "local", which is exactly the kind of
false claim the skill's Data UI section bans.

Shared vocabulary for the value: `local` and `aws-demo`, plus `replay` for the recorded mode. Empty
means unknown.

1. **`services/orchestrator`** (`agent-engineer`): add `environment` to `PingResponse` and
   `PingController`, bound with `@Value("${saiman.environment:}")`. **The default is the empty
   string, deliberately not `local`.** A `local` default would re-create the bug the moment the
   variable is missing on a deployment: the banner would confidently say "local" on AWS again.
2. **`web/`** (`frontend`): the banner reads `environment` from the ping query. Known value, name it.
   Empty, missing or a failed ping, say `Testnet only. No real money moves.` and claim no
   environment. Replay mode keeps showing the capture's own `environment` string, unchanged.
3. **`deploy/`** (`infra`): `SAIMAN_ENVIRONMENT: local` on the orchestrator service in
   `deploy/compose/docker-compose.yml` (around line 289, the orchestrator `environment:` block, not
   the `app-common-env` anchor) and `SAIMAN_ENVIRONMENT = "aws-demo"` in
   `deploy/terraform/aws/demo-lite/containers.tf` in `local.app_env.orchestrator` (around line 64).
   Plus `SAIMAN_ENVIRONMENT=` in `.env.example`.

Both sides are mandatory in the same PR: `tests/task.tftest.hcl` run `ecs_names_match_compose`
fails in both directions when compose and the ECS container definitions disagree on an app's
environment names, and `tests/mutation-check.py` has dedicated mutations for exactly that
(`compose-gains-a-name-ecs-does-not-have`, `ecs-forgets-a-name-compose-gives-the-app`).

Only the orchestrator gets the variable. It is the only service that serves `/api/v1/ping`, and a
narrower surface keeps the parity diff to one name per side.

## T0: baseline, before any code changes

Both targets ship in PR 0 and are run by the human on that branch, before PR 1 exists.

1. `make up`, then `make ui-shots` (defaults to `TAG=before`). Captures the "before" set into
   `build/ui-shots/before/{desktop,mobile}/`, which is gitignored. No image is committed.
2. `make ui-bundle`. Builds and prints the gzipped size of every JS and CSS chunk, with font files
   listed separately because they are already compressed and do not belong in the script budget
   even though they are on the wire. Writes `web/reports/bundle-size.json`. Every later PR quotes
   its delta against this one run.

Pages, at 1440x900 deviceScaleFactor 2 and again at 390x844, light theme only: home, runs index, a
run detail for a completed run, approvals, ledger, one payment detail, reconciliation, spend. Each
page is captured twice, viewport and full page.

### How `make ui-shots` keeps the token out of every artefact

- The target passes `SAIMAN_E2E_TOKEN_FILE=$(CURDIR)/secrets/api_operator_token`, exactly like
  `e2e-live`. The path is absolute on purpose: `pnpm --dir web` runs Playwright in `web/`, so a
  relative path resolves to `web/secrets/...` and fails with ENOENT. Both targets first test that
  the file exists (existence only, the file is never read) and point at `make auth-tokens` if not.
  The file is read by `web/e2e/live/live-test.ts`, which already existed for the live acceptance
  run, and the capture spec reuses that fixture rather than reading `secrets/` again. No new code in
  this repository opens a file under `secrets/`.
- `playwright.shots.config.ts` sets `trace: "off"`, `video: "off"` and `screenshot: "off"`, with the
  `list` reporter rather than `html`. A trace or a HAR embeds the `Authorization` header and the
  HTML report embeds the trace, so none of them is produced.
- The token is seeded into `sessionStorage` before the first navigation, so the connect screen is
  never reached. Every capture asserts the URL is not `/connect` first and fails rather than
  photographing the token entry form.
- Run and payment ids are discovered from the rendered tables, not hardcoded, and written to
  `manifest.json` beside the images so the "after" set covers the same rows.

## PR breakdown

Small and reviewable, in this order. `frontend` owns `web/` unless stated otherwise; `test-runner`
verifies each one, `reviewer` reviews each one once. `security-auditor` is not triggered: no
payment, wallet, budget or auth code is touched. PR 2 adds one read-only field to an existing
unauthenticated endpoint, which is worth one line in the review description and no audit.

Delegation prompts must be self-contained and must list exact file paths plus
`~/.claude/skills/ui-taste/SKILL.md`, because subagents do not see the planning conversation.

### PR 0: this plan and the baseline tooling

Files: `docs/design/ui-refresh.md`, `Makefile`, `web/package.json`,
`web/playwright.shots.config.ts`, `web/playwright.config.ts` (test ignore only),
`web/e2e/shots/ui-shots.spec.ts`, `web/scripts/bundle-size.mjs`.

No UI code changes: nothing under `web/src/` is touched. The human runs T0 on this branch, then
merges, and PR 1 starts from there.

### PR 1: tokens and typography foundation

Files: `web/src/index.css`, `web/package.json`, `web/src/routes/__root.tsx` (font import only),
`web/src/routes/index.tsx` (display face class only).

- The full ink token set from the skill, light and dark, dark unused.
- Inter self-hosted; Instrument Serif imported on the home route only, latin subset only.
- `tabular-nums` utility, focus ring on `--info`, `--fg-decorative` for dividers and disabled only.
- No layout change at all. The before/after diff should be colour and type and nothing else.
- The only PR in this phase that adds dependencies.

Also in this PR, decided after the T0 baseline:

- First commit of the PR: pin `locale: "en-GB"` and `timezoneId: "UTC"` in
  `web/playwright.shots.config.ts`, then the human re-takes `make ui-shots TAG=before` on the
  unchanged UI. The baseline was captured under Playwright's default `en-US`, so without this the
  before and after sets would differ in date format alone.
- `Makefile`: the token guard tests `-s` (exists and non-empty) instead of `-f`. An empty file
  passes `-f` and then fails inside Playwright with a message that the variable is unset, which it
  is not. `-s` is the same predicate `scripts/auth-tokens.sh` uses ("missing or empty"). Done on
  this branch.
- `web/reports/bundle-size.json` **stays gitignored** (`web/.gitignore` already lists `reports/`).
  The chunk file names carry content hashes and the file carries a timestamp, so a committed copy
  would produce a noisy diff on every build and say nothing a reviewer can use. The baseline is
  recorded as text instead, below, and each PR quotes its delta against it.

Bundle baseline, `make ui-bundle` on `main` before any UI change, 2026-10-20: **184.8 KB gzip** of
JS and CSS, **0 KB** of fonts. Largest chunks: `index-*.js` 94.7 KB, `link-*.js` 20.7 KB,
`hooks-*.js` 18.0 KB, `utils-*.js` 8.4 KB, `index-*.css` 5.2 KB, `runs._runId-*.js` 4.5 KB.

Measured after PR 1, same build flags: **186.2 KB gzip** of JS and CSS, so **+1.4 KB**, all of it
CSS (5.2 to 6.3 KB) plus a small chunk for the font import. Fonts go from 0 to **251.8 KB raw** in
`dist`, but that is every Inter subset on disk; a page fetches only what its text needs, so the home
page costs Inter latin (47 KB) plus Instrument Serif latin (20.5 KB), and latin-ext (83 KB) arrives
only when Turkish text appears.

### PR 2: environment banner and app shell

Files: `web/src/routes/__root.tsx`, `web/src/lib/banner-text.ts`, `web/src/lib/api/ping.ts`,
`services/orchestrator/.../system/PingResponse.java` and `PingController.java` (`agent-engineer`),
`deploy/compose/docker-compose.yml`, `deploy/terraform/aws/demo-lite/containers.tf`, `.env.example`
(`infra`).

- The three layer banner fix above, with the empty default.
- Nav, skip link, hover/focus/press states on every control.
- Em dash sweep across UI strings. Six sites in five files today (`grep -rn "—" web/src`, tests and
  the comment in `telemetry.ts` excluded): `lib/money.ts:15` (`UNAVAILABLE`, a shared constant that
  `payment-intents-panel.tsx` and `formatMoney` callers render), `routes/__root.tsx:35` (the banner),
  `components/ledger/payments-table.tsx:71` (a payment with no run),
  `components/ledger/reconciliation.tsx:243`, and two in `components/ledger/payment-detail.tsx`
  (lines 164 and 165, "Books" and "Chain" values). The PR description states how many were changed.
  The skill's "period or comma" replacement does not apply to a table placeholder, so what stands in
  for "no value" (`n/a`, `none`, or an empty cell with screen-reader text) is a design decision made
  once, in `UNAVAILABLE`, not six separate deletions.
- **Status badges do not differentiate.** In the after-screenshots `Completed` and `Failed` are the
  same neutral bordered pill; only the glyph and the word tell them apart. Give each status its
  token (`--success`, `--danger`, `--warning`, `--info`) through the skill's `oklab` tint recipe.
  The glyph and the word stay: colour is never the only carrier (`StatusBadge` already does this
  and must keep doing it).
- One date and time helper, moved into PR 1 and already done there. The remaining call sites in
  `components/run/runs-table.tsx`, `components/ledger/payments-table.tsx`,
  `components/ledger/payment-detail.tsx` and `components/ledger/reconciliation.tsx`.
- Tests: the orchestrator's ping test gains an assertion for the empty default; a `web` unit test
  covers known value, empty value and failed ping.

### PR 3: home page

Hero with the Instrument Serif `h1`, lede at 60 to 75ch, one primary CTA, system status card,
recent runs table, designed empty state. At most one calm signature element, and if nothing earns
it, none. No shader, no 3D, no glass.

Three findings from the after-screenshots, all for this PR:

- **The hero is too small for the face.** Instrument Serif at `text-3xl` reads thinner and weaker
  than the bold Inter heading it replaced; the display face is currently a downgrade. Size it like
  a display line, for example `clamp(2.5rem, 5vw, 4.5rem)`, and retune `letter-spacing` at that
  size (a face set large needs tighter tracking than the same face at 30px). The skill's
  "The one editorial moment" section gives 1.05 line-height and -0.015em as the starting point, not
  the finishing point.
- **Two primary buttons compete.** "Start a research run" and "Check again" are both solid ink.
  "Check again" becomes secondary (outline or ghost). One primary action per screen.
- **Wire the shadow tokens.** `--shadow-xs/sm/md` exist in `:root` but `shadow-sm` still compiles
  to Tailwind's own default, because a shadow value has to be composed with its colour. Either map
  them through `@theme` properly or use `shadow-[var(--shadow-xs)]`, and make resting cards, hover
  and overlays use the three steps the skill defines.

The system status card currently shows a trace id and the database time. That is useful to the
person who built it and noise to anyone else. Reduce it to a plain "Services reachable" state with
the technical fields behind a disclosure, or move the card below the recent runs. It must keep
reporting a failure honestly, so only the success presentation gets quieter.

### PR 4: run detail and micro-animation

`web/src/routes/runs.$runId.tsx` and `web/src/components/run/*`. Stepper, timeline, budget meter,
payment status transition (`signed` to `settled`), approval card resolving, report panel. Motion
only on state change, never on arrival. Every animation needs a reduced-motion branch that lands in
the correct end state.

The budget bars are ink from PR 1, which is right for ordinary consumption. Past 80 percent of the
budget they switch to `--warning`, and colour must not be the only signal: the bar gets a word or an
icon beside it saying the budget is nearly spent. The threshold is presentation only and never
money maths, the same rule the `<meter>` already follows.

### PR 5: ledger and reconciliation

**Blocked** until the ledger finding under "Baseline findings" is resolved. Nothing in this phase may
hide or soften the red balance warning.

`web/src/components/ledger/*`, `web/src/routes/ledger.*`, `web/src/routes/reconciliation.*`.
Tabular figures, right-aligned numeric columns, one row height, hairline separators, no zebra,
status badges from the fixed set, designed empty states.

- Balances on the account's normal side (`0.595 Cr`). One account stays awkward:
  `buyer:...:wallet:available` is an asset with a credit balance (`-0.30`), because wallet accounts
  record flows rather than faucet balances (ADR-0017, no opening entry). Say what it is, net
  outflow since the ledger began, instead of dressing it as a balance.
- The payments table's "Updated" column is mostly the time of the last reconciliation check, not
  of the payment: the scheduler runs every 5 minutes and sets `updated_at = now()` on every payment
  it checks (`ReconciliationRepository.java:256`). Show `created_at`, or label the column
  "Last checked".
- "Nothing booked yet" in the Buyer column and an empty Run column describe a payment that only
  the seller reported. **Confirmed by SQL on 2026-10-20:** none of the 45 seller-only payments
  (38 SETTLED, 6 SETTLE_FAILED, 1 CREDITED) has a matching row in `orchestrator.payment_intent`,
  while the control query matched 28 of 28 buyer-booked payments, so the join is sound. They came
  from a client that paid seller-api directly, almost certainly the console buyer. Label them
  "Direct client, not from a run", not "Nothing booked yet", which implies something is pending.
- **Long ids break mid-word.** The ledger account code wraps as `wallet:av/ailable` and the
  reconciliation payer breaks arbitrarily. Shorten addresses to `0xdd54...4795` with the full value
  in a `title` and a copy affordance, and stop mid-word breaking on identifier columns.

### PR 6: consistency pass

Approvals, spend, revenue, runs index, connect, new run. Alignment with the system only, no
redesign.

The Spend page shows the shared model budget as `$0.000000 of $0.700000`, six decimals on both
sides. That is `formatUsdMicros` in `components/spend/spend-overview.tsx`, micro-dollar precision on
purpose. A cap does not need six decimals: show the cap with two (`$0.70`) and the amount spent with
as many as keep a sub-cent figure honest (the rest of the app already uses four, `$0.0069`).

## Verification, every PR

- Before and after screenshots at both viewports, light theme.
- The 14 point visual self-critique checklist scored pass/fail, with the fix named for every fail.
  No merge with a fail in items 1 to 9. The self-critique happens before the PR is shown, not after.
- Bundle delta against the T0 baseline, per chunk, gzipped, fonts listed separately. Measure it with
  `make ui-bundle`, never a bare `pnpm build`. `ui-bundle` depends on `web-build`, which sets
  `VITE_OTEL_ENABLED=true`; without that flag the OpenTelemetry imports in `src/lib/telemetry.ts`
  are tree-shaken and the total comes out about 22 KB lower, which looks like an improvement and is
  only a different build.
- `pnpm --dir web test`, `lint`, `typecheck`, plus `./gradlew check` for PR 2.
- `pnpm --dir web e2e`. It is acceptance, not optional: PR 2 moved the connection status out of
  the `Main` nav landmark, which no unit test, type check or lint could notice, and two auth
  specs failed on it. Nothing ships as finished without this run.
- `@axe-core/playwright` run on the touched routes.
- Reduced-motion verified by emulating `prefers-reduced-motion: reduce` and confirming the end state
  is correct, not merely that the animation is shorter.

## Baseline findings (T0, 2026-10-20)

### Open: the ledger page claims "each book always balances" and shows a red warning

The baseline shows PLATFORM book debits 0.005 against credits 0.00, and Seller book debits 0.645
against credits 0.65, while the Buyer book balances at 0.80 / 0.80. Across all three books debits
and credits both total 1.45.

Established by reading the code, nothing run:

- The invariant the system enforces is **per entry, per asset**: the deferred constraint trigger
  `check_entry_balanced` groups by `(asset, decimals)` (`V1__ledger.sql`) and
  `JournalEntry.requireBalanced` does the same in the domain code. ADR-0017 says the same. No code
  and no migration from V2 to V8 relates a posting's account book to the entry's book.
- A cross-book entry is the designed shape of a reconciliation adjustment. `ChainReconciler.addLegs`
  posts Dr `platform:suspense:usdc` (account book PLATFORM) against Cr `seller:<payTo>:wallet`
  (account book SELLER) in one entry labelled PLATFORM. The 0.005 debit and the 0.005 wallet credit
  are the two legs of one such entry.
- The page groups by **account** book (`JournalRepository.trialBalance`, `checkTrialBalance` in
  `web/src/lib/ledger-model.ts`) and the copy at `routes/ledger.index.tsx:37` promises each book
  balances. That is stronger than what the ledger guarantees, so it holds only until reconciliation
  parks something in suspense. M4's own acceptance wording is the whole-ledger sum: "trial balance
  sums to 0" (`docs/PROGRESS.md:186`).
- Nothing in the code posts a REVERSAL of an ADJUSTMENT. ADR-0017 says a human clears suspense with
  one, but the only REVERSAL producer is the automatic release reversal in `PaymentBook`, and there
  is no endpoint or make target for the manual case.
- The "Updated" column is the last reconciliation check, not the payment time (see PR 5).
- `buyer:...:wallet:available` at `-0.30` is not a bug: ENCUMBER credits it, RELEASE debits it,
  nothing funds it (ADR-0017, "flows, not faucet balances"). 0.40 encumbered, 0.30 settled, 0.10
  released gives `-0.30`, the net outflow.

Probable cause of this instance, **not confirmed until the SQL below is run**:
`make ledger-tamper-demo` raises both postings of the latest SALE by 5000 atomic units, which is
exactly 0.005 USDC, and `docs/PROGRESS.md:186` records that acceptance run. The script refuses to
run unless the postgres container is the local compose project, which would explain why the AWS demo
was balanced. The tamper also inflates `revenue:data` by the same 0.005 and reconciliation never
corrects revenue (`RevenueReport.java:12`), so the Revenue page should show gross 0.595 against
chain-verified 0.590.

Not yet explained: the 17 payments on the first page of the list with "Nothing booked yet" and no
run (the list has a "Load more" button, so there may be others). Only the orchestrator
publishes buyer events (`PaymentEventPublisher`) and only seller-api publishes seller events
(`SettlementRecorder`), and `payment_intent.run_id` is `NOT NULL`, so a payment made through the
orchestrator always has a run. These payments therefore came from a client that paid seller-api
directly, most likely the console buyer (`make x402-buy`, `make rag-ask`; evals never pay x402,
`m6-hardening.md:7`). If their `payer` is **not** the orchestrator's wallet, nothing is missing. If
it **is**, buyer events were lost, which is a real bug to look at next (the ledger inbox and the
`saiman.ledger.dlt` counter).

Read-only SQL for the human (`make psql`), run inside `SET default_transaction_read_only = on;`:

```sql
-- 1. The ledger as a whole must sum to zero per asset. Expected: 0 rows with a non-zero sum.
SELECT asset, sum(CASE side WHEN 'DEBIT' THEN amount_atomic ELSE -amount_atomic END) AS net
  FROM ledger.posting GROUP BY asset;

-- 2. Every adjustment, with the book of each leg's account.
SELECT e.id, e.description, e.recorded_at, e.payment_key,
       p.account_code, a.book AS account_book, p.side, p.amount_atomic
  FROM ledger.journal_entry e
  JOIN ledger.posting p ON p.entry_id = e.id
  JOIN ledger.account a ON a.code = p.account_code AND a.asset = p.asset
 WHERE e.kind = 'ADJUSTMENT' ORDER BY e.recorded_at, p.side;

-- 3. What reconciliation recorded. A tamper shows AMOUNT_MISMATCH with 15000 against 10000.
SELECT m.kind, m.ledger_amount_atomic, m.chain_amount_atomic, m.detected_at,
       m.adjustment_entry_id, r.started_at
  FROM ledger.reconciliation_mismatch m JOIN ledger.reconciliation_run r ON r.id = m.run_id
 ORDER BY m.detected_at;

-- 4. Who paid, by state. created_at is the payment time, updated_at is not.
SELECT payer, buyer_state, seller_state, chain_state, count(*), min(created_at), max(created_at)
  FROM ledger.payment GROUP BY 1, 2, 3, 4 ORDER BY min(created_at);

-- 5. Which topics the ledger has consumed.
SELECT topic, count(*) FROM ledger.inbox GROUP BY topic ORDER BY topic;
```

Proposed fix, as its own PR and not part of this phase: keep the warning and make the page tell the
truth about what the system guarantees. State the real invariant (the whole ledger balances per
asset) as the check; show a non-zero suspense balance as an open finding that links to the
reconciliation mismatch; keep per-account-book subtotals as information; and decide whether a
sanctioned way to clear suspense should exist at all. That last point is a ledger design question.

## Open questions

1. Resolved. `terraform test -filter=tests/task.tftest.hcl` is allowed for PR 2 under fixed
   conditions, and the baseline was run on 2026-10-20: 25 passed, 0 failed. Evidence that it needs
   no credentials: `tests/task.tftest.hcl:12` declares `mock_provider "aws"` at file level, so it
   covers every run in the file; no run block overrides it with `providers = {...}`; the only nested
   module, `tests/compose`, declares no `provider`, `resource` or `data` block at all and is invoked
   with `command = plan`. The module's real `provider "aws"` (`versions.tf:12`) is replaced by the
   mock. Always run it as:

   ```bash
   terraform init -backend=false
   env -u AWS_PROFILE -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
     AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true \
     terraform test -filter=tests/task.tftest.hcl
   ```

2. `scripts/capture-demo/capture-demo.sh` defaults `CAPTURE_ENVIRONMENT` to `local docker compose`,
   which is outside the `local` / `aws-demo` / `replay` vocabulary. Aligning it would be a change to
   a demo command, which is out of scope here. Left as is; the replay banner keeps rendering the
   capture's string verbatim.
3. Resolved: the local database holds 15 runs, 11 completed and 4 failed, dated 2026-10-01 to
   2026-10-05. T0 needs no new run. Pick one completed run for the detail shot, and note that the
   runs table's "Created" column will show dates rather than times for this data.
