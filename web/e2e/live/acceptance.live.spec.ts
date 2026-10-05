import { expect, test } from "./live-test";

/**
 * M5 acceptance against the real stack: a new user starts a run, approves the payment and sees it
 * land in the ledger. Needs `make up` (all services, funded testnet wallet) and costs a real
 * Base Sepolia test payment. The run page's "In the ledger" panel polls for up to 60 s after the
 * terminal event; the ledger consumes Kafka asynchronously, so allow 90 s here.
 */
test("start a run, approve the payment, see it in the ledger", async ({ page }) => {
  await page.goto("/runs/new");
  await page.getByRole("button", { name: /Try: THYAO/ }).click();
  const question = await page.getByLabel("Your question").inputValue();
  expect(question.length).toBeGreaterThan(2); // the default example question
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/);

  // The report (success) or the failure banner both end the run.
  const done = page.locator("main header").getByText(/Completed|Failed/);
  const approval = page.getByRole("region", { name: /Approval needed/ });

  // A payment above the approval threshold pauses the run for up to five minutes.
  await expect(approval.or(done)).toBeVisible({ timeout: 120_000 });
  if (await approval.isVisible()) {
    await approval.getByRole("button", { name: /Approve/ }).click();
  }

  await expect(done).toBeVisible({ timeout: 5 * 60_000 });
  await expect(page.locator("main header")).toContainText("Completed");

  const panel = page.getByRole("region", { name: "In the ledger" });
  const table = panel.getByRole("table", { name: /payments in the ledger/ });
  await expect(
    table,
    "the ledger should have booked the run's payment within 90 s of the run ending",
  ).toBeVisible({ timeout: 90_000 });
  await expect(table.getByRole("row")).not.toHaveCount(1); // header + at least one payment

  await table
    .getByRole("link", { name: /ledger entries/ })
    .first()
    .click();
  await expect(page).toHaveURL(/\/ledger\/payments\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { name: "Journal entries" })).toBeVisible();
  await expect(page.getByRole("table").first()).toBeVisible();
});
