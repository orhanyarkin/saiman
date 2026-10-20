# UI refresh (post-M6 presentation pass)

Goal: bring `web/` to a visual standard that holds up on LinkedIn and in the README without
weakening a fintech dashboard's two real jobs, trust and readability. This is a presentation pass,
not a feature milestone; PLAN.md has no milestone for it.

The taste rules, design tokens, motion grammar, anti-slop list and the 14 point visual self-critique
checklist live outside this repository in `~/.claude/skills/ui-taste/SKILL.md`, with reference
screenshots in `~/.claude/skills/ui-taste/references/`. Every PR below is graded against that
checklist. Whoever picks this up must read that file first.

## Decisions already made

| Decision             | Value                                                                    | Note                                                                                                                                                                  |
| -------------------- | ------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Accent               | **Ink** (monochrome CTA)                                                 | `--primary: oklch(0.265 0.020 255)`, light theme. Colour then means status and nothing else.                                                                          |
| Focus ring           | `--info` blue, not the primary                                           | With an ink primary the ring must stay a distinct hue or focus stops reading as focus. 5.42:1 on surface.                                                             |
| UI typeface          | Inter Variable, `@fontsource-variable/inter@5.3.0`, OFL-1.1, self-hosted | latin 48 KB, latin-ext 85 KB. Turkish content pulls latin-ext.                                                                                                        |
| Display face         | Instrument Serif, `@fontsource/instrument-serif@5.3.0`, OFL-1.1          | Home `h1` only, loaded on that route only. latin 21 KB.                                                                                                               |
| Home `h1` language   | English                                                                  | So the display face ships **latin only**. Any Turkish in the `h1` would pull latin-ext (+12 KB) and must be a deliberate decision.                                    |
| Dark mode            | Tokens stored, not shipped                                               | The dark block goes into `index.css` as the skill shows. No toggle, no dark screenshots, light only this phase.                                                       |
| Motion library       | None by default                                                          | CSS plus the existing `tw-animate-css`. `motion` (54.6 KB gzip) is added only if a specific interaction is shown to be impossible in CSS, and then only after asking. |
| Em dashes in UI copy | Removed                                                                  | Accepted from the skill's anti-slop list. Applies to interface strings only, not to documentation.                                                                    |

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

- The target passes `SAIMAN_E2E_TOKEN_FILE=secrets/api_operator_token`, exactly like `e2e-live`.
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

### PR 2: environment banner and app shell

Files: `web/src/routes/__root.tsx`, `web/src/lib/banner-text.ts`, `web/src/lib/api/ping.ts`,
`services/orchestrator/.../system/PingResponse.java` and `PingController.java` (`agent-engineer`),
`deploy/compose/docker-compose.yml`, `deploy/terraform/aws/demo-lite/containers.tf`, `.env.example`
(`infra`).

- The three layer banner fix above, with the empty default.
- Nav, skip link, hover/focus/press states on every control.
- Em dash sweep across UI strings, including the literal `{"—"}` in `__root.tsx`, the one rendered
  for a payment with no run in `web/src/components/ledger/payments-table.tsx`, and the banner
  string. The PR description states how many occurrences were changed.
- Tests: the orchestrator's ping test gains an assertion for the empty default; a `web` unit test
  covers known value, empty value and failed ping.

### PR 3: home page

Hero with the Instrument Serif `h1`, lede at 60 to 75ch, one primary CTA, system status card,
recent runs table, designed empty state. At most one calm signature element, and if nothing earns
it, none. No shader, no 3D, no glass.

### PR 4: run detail and micro-animation

`web/src/routes/runs.$runId.tsx` and `web/src/components/run/*`. Stepper, timeline, budget meter,
payment status transition (`signed` to `settled`), approval card resolving, report panel. Motion
only on state change, never on arrival. Every animation needs a reduced-motion branch that lands in
the correct end state.

### PR 5: ledger and reconciliation

`web/src/components/ledger/*`, `web/src/routes/ledger.*`, `web/src/routes/reconciliation.*`.
Tabular figures, right-aligned numeric columns, one row height, hairline separators, no zebra,
status badges from the fixed set, designed empty states.

### PR 6: consistency pass

Approvals, spend, revenue, runs index, connect, new run. Alignment with the system only, no
redesign.

## Verification, every PR

- Before and after screenshots at both viewports, light theme.
- The 14 point visual self-critique checklist scored pass/fail, with the fix named for every fail.
  No merge with a fail in items 1 to 9. The self-critique happens before the PR is shown, not after.
- Bundle delta against the T0 baseline, per chunk, gzipped, fonts listed separately.
- `pnpm --dir web test`, `lint`, `typecheck`, plus `./gradlew check` for PR 2.
- `@axe-core/playwright` run on the touched routes.
- Reduced-motion verified by emulating `prefers-reduced-motion: reduce` and confirming the end state
  is correct, not merely that the animation is shorter.

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
