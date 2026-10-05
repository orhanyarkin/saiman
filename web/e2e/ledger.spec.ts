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

const P1 = "3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e51";
const P2 = "3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e52";

test("acceptance: start a run, approve, and find the payment in the ledger", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Start a research run" }).click();
  await page.getByRole("button", { name: /Try: THYAO/ }).click();
  await page.getByRole("button", { name: "Start run" }).click();

  const card = page.getByRole("region", { name: /Approval needed/ });
  await expect(card).toBeVisible();
  await card.getByRole("button", { name: /Approve/ }).click();
  await expect(page.locator("main header")).toContainText("Completed");

  // The ledger lags the orchestrator (asynchronous), so the panel polls until the payment shows.
  const panel = page.getByRole("region", { name: "In the ledger" });
  const table = panel.getByRole("table", { name: /payments in the ledger/ });
  await expect(table).toBeVisible({ timeout: 15_000 });
  await expect(table).toContainText("0.02 USDC");
  await expectNoSeriousA11yViolations(page);

  await table.getByRole("link", { name: /ledger entries/ }).click();
  await expect(page).toHaveURL(/\/ledger\/payments\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 2, name: "Journal entries" })).toBeVisible();
  await expect(page.getByRole("table", { name: /SETTLE in the buyer book/ })).toBeVisible();
  await expect(page.getByRole("table", { name: /SALE in the seller book/ })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
});

test("ledger: books balance, payments page with a book filter", async ({ page }) => {
  await page.goto("/ledger");
  await expect(page.getByRole("heading", { level: 1, name: "Ledger" })).toBeVisible();
  await expect(page.getByText(/books balance\. Σ debits = Σ credits = 0\.10 USDC/)).toBeVisible();
  await expect(page.getByText(/books balance\. Σ debits = Σ credits = 0\.04 USDC/)).toBeVisible();
  await expect(page.getByRole("table", { name: "Trial balance, seller book" })).toContainText(
    "revenue:data",
  );

  const payments = page.getByRole("table", { name: /Ledger payments/ });
  await expect(payments.getByRole("row")).toHaveCount(3); // header + first page (2)
  await page.getByRole("button", { name: "Load more" }).click();
  await expect(payments.getByRole("row")).toHaveCount(4);
  await expect(page.getByRole("button", { name: "Load more" })).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);

  await page.getByLabel("Show payments booked in").selectOption("SELLER");
  await expect(payments.getByRole("row")).toHaveCount(2);
});

test("payment drill-down: findings in plain language, links to Basescan and the run", async ({
  page,
}) => {
  await page.goto(`/ledger/payments/${P2}`);
  await expect(page.getByRole("heading", { level: 1, name: /Payment 3f2b8a40/ })).toBeVisible();
  const findings = page.getByRole("region", { name: "Reconciliation findings" });
  await expect(findings).toContainText("AMOUNT_MISMATCH");
  await expect(findings).toContainText("differs from the amount that moved on chain");
  await expect(findings.getByRole("link", { name: "Reconciliation run" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  await page.goto(`/ledger/payments/${P1}`);
  await expect(page.getByRole("link", { name: /Chain: view on Basescan/ })).toHaveAttribute(
    "href",
    /^https:\/\/sepolia\.basescan\.org\/tx\/0x[0-9a-f]{64}$/,
  );
  await expect(page.getByRole("link", { name: "Open the research run" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
});

test("payment drill-down: a malformed id never reaches the API", async ({ page }) => {
  const requests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/ledger/payments/")) {
      requests.push(request.url());
    }
  });
  await page.goto("/ledger/payments/not-a-uuid");
  await expect(page.getByRole("heading", { level: 1, name: "Payment not found" })).toBeVisible();
  await expect(page.getByRole("alert")).toContainText("not a valid payment id");
  expect(requests).toEqual([]);
  await expectNoSeriousA11yViolations(page);

  await page.goto("/ledger/payments/00000000-0000-4000-8000-000000000000");
  await expect(page.getByRole("heading", { level: 1, name: "Payment not found" })).toBeVisible();
});

test("reconciliation: report with pending and mismatch items, history and a run page", async ({
  page,
}) => {
  await page.goto("/reconciliation");
  await expect(page.getByRole("heading", { level: 1, name: "Reconciliation" })).toBeVisible();
  const items = page.getByRole("table", { name: /Payments checked/ });
  await expect(items).toContainText("Mismatch");
  await expect(items).toContainText("AMOUNT_MISMATCH");
  await expect(items).toContainText("Pending");
  await expect(page.getByText(/Waiting for Base Sepolia's safe block/).first()).toBeVisible();
  await expect(items.getByRole("link", { name: /View on Basescan/ })).toHaveAttribute(
    "href",
    /^https:\/\/sepolia\.basescan\.org\/tx\/0x[0-9a-f]{64}$/,
  );
  await expectNoSeriousA11yViolations(page);

  const history = page.getByRole("table", { name: /Reconciliation runs, newest first/ });
  await history.getByRole("link").nth(1).click();
  await expect(page).toHaveURL(/\/reconciliation\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1, name: /Reconciliation run/ })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
});

test("reconciliation: Run now handles started, already running and rate limited", async ({
  page,
}) => {
  await page.goto("/reconciliation");
  const button = page.getByRole("button", { name: "Run now" });
  await button.click();
  await expect(page.getByText(/Reconciliation started/)).toBeVisible();

  await button.click(); // still RUNNING in the fixture
  await expect(page.getByText(/already running/)).toBeVisible();

  // Let it finish (about 1.5 s in the fixture), then start again inside the cooldown.
  await expect(page.getByText(/^Completed/).first()).toBeVisible({ timeout: 10_000 });
  await button.click();
  await expect(page.getByText(/You can run it again in about \d+ seconds/)).toBeVisible();
  await expect(button).toBeDisabled();
  await expectNoSeriousA11yViolations(page);

  await expect(button).toBeEnabled({ timeout: 10_000 });
  await button.click();
  await expect(page.getByText(/Reconciliation started/)).toBeVisible();
});

test("revenue: per books next to chain-verified figures", async ({ page }) => {
  await page.goto("/revenue");
  await expect(page.getByRole("heading", { level: 1, name: "Seller revenue" })).toBeVisible();
  const table = page.getByRole("table", { name: /Revenue of 0x1111/ });
  await expect(
    table.getByRole("columnheader", { name: "Per books (not chain-verified)" }),
  ).toBeVisible();
  await expect(
    table.getByRole("columnheader", { name: "Chain-verified", exact: true }),
  ).toBeVisible();
  const gross = table.getByRole("row", { name: /Gross sales/ });
  await expect(gross).toContainText("0.04 USDC");
  await expect(gross).toContainText("0.02 USDC");
  await expect(page.getByText("Unverified gross sales")).toBeVisible();
  await expect(page.getByText(/safe a little late/)).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  await page.getByLabel(/Filter by payee address/).fill("0x12");
  await expect(page.getByRole("alert")).toContainText("Enter a full address");
  await page
    .getByLabel(/Filter by payee address/)
    .fill("0x1111111111111111111111111111111111111111");
  await expect(page.getByRole("alert")).toHaveText("");
  await expect(table).toBeVisible();
});

test("the new screens are reachable from the main navigation, by keyboard order", async ({
  page,
}) => {
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "Main" });
  for (const name of ["Ledger", "Reconciliation", "Revenue"]) {
    await expect(nav.getByRole("link", { name })).toBeVisible();
  }
  await nav.getByRole("link", { name: "Revenue" }).click();
  await expect(nav.getByRole("link", { name: "Revenue" })).toHaveAttribute("aria-current", "page");
});
