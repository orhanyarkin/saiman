import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

/** Serious and critical axe violations fail the test; the rest is reported by axe only. */
async function expectNoSeriousA11yViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  const blocking = results.violations.filter(
    (v) => v.impact === "serious" || v.impact === "critical",
  );
  expect(blocking.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

test("runs list: keyset paging, then the run with its payment intents", async ({ page }) => {
  await page.goto("/runs");
  await expect(page.getByRole("heading", { level: 1, name: "Research runs" })).toBeVisible();
  const table = page.getByRole("table", { name: /Research runs/ });
  await expect(table.getByRole("row")).toHaveCount(3); // header + first page (2 runs)
  await expect(table).toContainText("Failed");
  await expectNoSeriousA11yViolations(page);

  await page.getByRole("button", { name: "Load more" }).click();
  await expect(table.getByRole("row")).toHaveCount(4);
  await expect(page.getByRole("button", { name: "Load more" })).toHaveCount(0);

  await table.getByRole("link", { name: /THYAO/ }).click();
  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/);
  const payments = page.getByRole("table", { name: "Payment intents of this run" });
  await expect(payments).toContainText("Settled");
  await expect(payments).toContainText("Held");
  await expect(payments.getByRole("link", { name: /Basescan/ })).toHaveAttribute(
    "href",
    /^https:\/\/sepolia\.basescan\.org\/tx\/0x[0-9a-f]{64}$/,
  );
  await expectNoSeriousA11yViolations(page);
});

test("landing lists the recent runs", async ({ page }) => {
  await page.goto("/");
  const table = page.getByRole("table", { name: /most recent research runs/ });
  await expect(table.getByRole("row")).toHaveCount(4);
  await expectNoSeriousA11yViolations(page);
});

test("approvals page: approve from the list, badge in the nav", async ({ page }) => {
  await page.goto("/runs/new");
  await page.getByLabel("Your question").fill("THYAO son açıklamalar neler?");
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.getByRole("region", { name: /Approval needed/ })).toBeVisible();
  const runId = /\/runs\/([0-9a-f-]{36})$/.exec(page.url())?.[1] ?? "";
  expect(runId).not.toBe("");

  await page
    .getByRole("navigation", { name: "Main" })
    .getByRole("link", { name: /Approvals/ })
    .click();
  await expect(page.getByRole("heading", { level: 1, name: "Pending approvals" })).toBeVisible();
  await expect(page.getByRole("link", { name: /Approvals.*pending/ })).toBeVisible();
  const row = page.getByRole("listitem").filter({ has: page.locator(`a[href="/runs/${runId}"]`) });
  await expect(row).toContainText("0.02 USDC");
  await expect(row).toContainText("0x1111111111111111111111111111111111111111");
  await expectNoSeriousA11yViolations(page);

  await row.getByRole("button", { name: /^Approve/ }).click();
  // The decision refetches the list, so the decided row leaves it.
  await expect(row).toHaveCount(0);
  await page.goto(`/runs/${runId}`);
  await expect(page.locator("main header")).toContainText("Completed");
});

test("spend page: cap, remaining, limits, per-tool table and day validation", async ({ page }) => {
  await page.goto("/spend");
  await expect(page.getByRole("heading", { level: 1, name: "Spend control" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Daily cap" })).toBeVisible();
  await expect(page.getByText("1,000,000", { exact: false })).toHaveCount(0);
  const remaining = page
    .getByRole("heading", { name: "Remaining" })
    .locator("xpath=ancestor::*[3]");
  await expect(remaining).toContainText("0.94 USDC");
  const byTool = page.getByRole("table", { name: /by tool and status/ });
  await expect(byTool.getByRole("columnheader")).toHaveCount(4);
  await expect(byTool).toContainText("HELD");
  await expect(page.getByText(/outside the\s+language model/)).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  await page.getByLabel("Day (UTC)").fill("");
  await expect(page.getByRole("alert").filter({ hasText: "valid date" })).toBeVisible();
});

test("new-run form takes its defaults from the spend limits", async ({ page }) => {
  await page.goto("/runs/new");
  await expect(page.getByLabel(/Budget for this run/)).toHaveValue("0.05");
  await expect(page.getByText(/Payments above 0.01 USDC ask for your approval/)).toBeVisible();
});
