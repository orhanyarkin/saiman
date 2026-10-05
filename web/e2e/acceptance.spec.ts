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

test("first run: landing, start, approve, completed report", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expect(page.getByText(/Testnet only/)).toBeVisible();
  await expect(page.getByRole("heading", { name: "System check" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  await page.getByRole("link", { name: "Start a research run" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Start a research run" })).toBeVisible();
  await page.getByRole("button", { name: /Try: THYAO/ }).click();
  await expect(page.getByLabel("Your question")).not.toHaveValue("");
  await expect(page.getByLabel(/Budget for this run/)).toHaveValue("0.05");
  await expectNoSeriousA11yViolations(page);
  await page.getByRole("button", { name: "Start run" }).click();

  // The scripted run pauses at the approval and waits for the human.
  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/);
  const card = page.getByRole("region", { name: /Approval needed/ });
  await expect(card).toBeVisible();
  await expect(card).toContainText("0.02 USDC");
  await expect(card).toContainText("0x1111111111111111111111111111111111111111");
  await expectNoSeriousA11yViolations(page);

  await card.getByRole("button", { name: /Approve/ }).click();

  await expect(page.getByRole("heading", { name: "Report" })).toBeVisible();
  await expect(page.getByText("Status: Completed")).toBeVisible();
  await expect(page.getByRole("link", { name: /Özel Durum/ })).toHaveAttribute(
    "href",
    /^https:\/\/www\.kap\.org\.tr\//,
  );
  await expect(page.getByRole("link", { name: /Basescan/ })).toHaveAttribute(
    "href",
    /^https:\/\/sepolia\.basescan\.org\/tx\/0x[0-9a-f]{64}$/,
  );
  await expect(page.getByRole("region", { name: /Approval needed/ })).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);
});

test("rejecting the payment ends the run as failed", async ({ page }) => {
  await page.goto("/runs/new");
  await page.getByLabel("Your question").fill("THYAO son açıklamalar neler?");
  await page.getByRole("button", { name: "Start run" }).click();
  const card = page.getByRole("region", { name: /Approval needed/ });
  await expect(card).toBeVisible();
  await card.getByRole("button", { name: "Reject" }).click();
  await expect(page.getByText("Status: Failed")).toBeVisible();
});

test("the form validates the question before sending", async ({ page }) => {
  await page.goto("/runs/new");
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.getByText(/between 3 and 500 characters/)).toBeVisible();
  await expect(page).toHaveURL(/\/runs\/new$/);
});

test("keyboard: the skip link is the first tab stop", async ({ page }) => {
  await page.goto("/");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to main content" })).toBeFocused();
});

test("an unknown run shows a clear error", async ({ page }) => {
  await page.goto("/runs/00000000-0000-0000-0000-000000000000");
  await expect(page.getByRole("heading", { level: 1, name: "Run not found" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
});
