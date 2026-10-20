import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

import type { Page } from "@playwright/test";

import { expect, test } from "../live/live-test";

/**
 * Captures the before and after screenshot sets for the UI refresh (docs/design/ui-refresh.md),
 * against a real stack on :8088. Run through `make ui-shots` (TAG=before by default), never in CI.
 *
 * The operator token is seeded into sessionStorage by `../live/live-test`, the same path
 * `make e2e-live` uses: the file is read there and nowhere else. The connect screen is therefore
 * never reached, and every capture asserts that before it fires, so the token entry form cannot end
 * up in a screenshot.
 *
 * Both run and payment ids are discovered from the UI rather than hardcoded, and written to
 * `manifest.json` next to the images, so the "after" set can be captured from the same rows.
 */
const OUT = resolve(process.env.SAIMAN_UI_SHOTS_DIR ?? "build/ui-shots/before");

/** A capture is only valid on the page it claims: a redirect to /connect means the token is gone. */
async function shoot(page: Page, project: string, name: string) {
  await expect(page, "a redirect to /connect would photograph the token entry form").not.toHaveURL(
    /\/connect/,
  );
  // The app announces its route through the document title, so waiting on it beats a fixed sleep.
  await expect(page).toHaveTitle(/.+/);
  // Bounded: a run page holds an SSE stream open, so the network never goes idle there.
  await page.waitForLoadState("networkidle", { timeout: 5_000 }).catch(() => undefined);
  const dir = join(OUT, project);
  mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: join(dir, `${name}.png`), animations: "disabled" });
  await page.screenshot({
    path: join(dir, `${name}-full.png`),
    fullPage: true,
    animations: "disabled",
  });
}

test("capture the dashboard", async ({ page }, testInfo) => {
  const project = testInfo.project.name;

  await page.goto("/");
  await shoot(page, project, "01-home");

  await page.goto("/runs");
  await expect(page.getByRole("table")).toBeVisible();
  await shoot(page, project, "02-runs");

  // The first completed run: its detail page is the one with a report, a full timeline and payments.
  const completed = page
    .getByRole("row")
    .filter({ hasText: "Completed" })
    .first()
    .getByRole("link")
    .first();
  await expect(
    completed,
    "no completed run in the runs table: start one from the UI first",
  ).toBeVisible();
  const runHref = await completed.getAttribute("href");
  expect(runHref).toBeTruthy();
  await page.goto(runHref ?? "/runs");
  await shoot(page, project, "03-run-detail");

  await page.goto("/approvals");
  await shoot(page, project, "04-approvals");

  await page.goto("/ledger");
  await shoot(page, project, "05-ledger");

  const payment = page.getByRole("link", { name: /payment details/ }).first();
  let paymentHref: string | null = null;
  if (await payment.isVisible()) {
    paymentHref = await payment.getAttribute("href");
    await page.goto(paymentHref ?? "/ledger");
    await shoot(page, project, "06-payment-detail");
  }

  await page.goto("/reconciliation");
  await shoot(page, project, "07-reconciliation");

  await page.goto("/spend");
  await shoot(page, project, "08-spend");

  mkdirSync(OUT, { recursive: true });
  writeFileSync(
    join(OUT, "manifest.json"),
    `${JSON.stringify({ capturedAt: new Date().toISOString(), runHref, paymentHref }, null, 2)}\n`,
  );
});
