import { expect, test } from "@playwright/test";

import { expectNoSeriousA11yViolations } from "./helpers";

/**
 * The static replay build (ADR-0026), served as files with no API behind it: VITE_DEMO_MODE=replay,
 * reading the bundled sample at /demo/capture.json. Runs in the `replay` Playwright project.
 */
const RUN = "6ad4354c-8e79-4b49-b5d9-d45eb9689b41";
const PAYMENT = "3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e52";
const RECON = "9b1f0c52-3a7e-4d68-8f21-5c0d1e2f3a61";

const BANNER =
  "Recorded on SAMPLE DATA from the e2e fixtures - not a real recording, 2026-10-01 - Base Sepolia testnet. Nothing on this page is live.";

const ROUTES = [
  "/",
  "/runs",
  `/runs/${RUN}`,
  "/runs/new",
  "/approvals",
  "/spend",
  "/ledger",
  `/ledger/payments/${PAYMENT}`,
  "/reconciliation",
  `/reconciliation/${RECON}`,
  "/revenue",
  "/connect",
];

for (const route of ROUTES) {
  test(`replay build: ${route} shows the banner, no live controls and passes axe`, async ({
    page,
  }) => {
    const apiRequests: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).pathname.startsWith("/api/")) {
        apiRequests.push(request.url());
      }
    });

    await page.goto(route);
    await expect(page.getByTestId("replay-banner")).toHaveText(BANNER);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    // No token UI, no live actions, no calls to an API.
    await expect(page.getByRole("button", { name: "Connect" })).toHaveCount(0);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(
      page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "New run" }),
    ).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Approve/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Run now" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Start run" })).toHaveCount(0);
    expect(apiRequests).toEqual([]);

    await expectNoSeriousA11yViolations(page);
  });
}

test("replay build: a recorded run shows its report with citations", async ({ page }) => {
  await page.goto(`/runs/${RUN}`);
  await expect(page.getByRole("heading", { name: "Report" })).toBeVisible();
  await expect(page.getByRole("table", { name: "Payment intents of this run" })).toBeVisible();
});

test("replay build: a page that is not in the recording says so", async ({ page }) => {
  await page.goto("/runs/00000000-0000-4000-8000-000000000000");
  await expect(page.getByRole("alert")).toContainText("not in this recording");
  await expect(page.getByTestId("replay-banner")).toBeVisible();
});
