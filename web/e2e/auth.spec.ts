import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { test as cold, expect as coldExpect } from "@playwright/test";

import {
  expectNoSeriousA11yViolations,
  OPERATOR_TOKEN,
  READER_TOKEN,
  TOKEN_STORAGE_KEY,
  test,
  expect,
} from "./helpers";

const SAMPLE_CAPTURE = readFileSync(
  resolve(import.meta.dirname, "fixtures/replay-capture.sample.json"),
);

/** Starts a run through the API as the operator and returns its id (no UI involved). */
async function startRunViaApi(
  request: import("@playwright/test").APIRequestContext,
  question: string,
): Promise<string> {
  const response = await request.post("/api/v1/runs", {
    headers: {
      Authorization: `Bearer ${OPERATOR_TOKEN}`,
      "X-Saiman-Csrf": "1",
      "Content-Type": "application/json",
    },
    data: { question },
  });
  expect(response.status()).toBe(202);
  return ((await response.json()) as { runId: string }).runId;
}

// ---- token flow: no helper, the tab starts without a token --------------------------------------

cold.describe("token flow", () => {
  cold(
    "a 401 opens the dialog; the token goes in a header only and never into storage or a URL",
    async ({ page }) => {
      const api: { url: string; auth: string | undefined; at: number }[] = [];
      let connectedAt = Number.POSITIVE_INFINITY;
      page.on("request", (request) => {
        const url = new URL(request.url());
        if (url.pathname.startsWith("/api/")) {
          void request.allHeaders().then((headers) => {
            api.push({ url: request.url(), auth: headers.authorization, at: Date.now() });
          });
        }
      });

      await page.goto("/runs");
      const dialog = page.getByRole("dialog", { name: "Connect to the API" });
      await coldExpect(dialog).toBeVisible();
      await coldExpect(dialog.getByText("The API needs a token.")).toBeVisible();
      await expectNoSeriousA11yViolations(page);

      // Keyboard: focus is inside the dialog, and the token field is a password input.
      const field = dialog.getByLabel("API token");
      await coldExpect(field).toHaveAttribute("type", "password");
      await field.fill(READER_TOKEN);
      connectedAt = Date.now();
      await field.press("Enter");

      await coldExpect(dialog).toBeHidden();
      await coldExpect(page.getByRole("table", { name: /Research runs/ })).toBeVisible();
      await coldExpect(page.getByRole("button", { name: "Disconnect" })).toBeVisible();

      // Every request after connecting carries the header; none carries the token in its URL.
      await page.waitForTimeout(200);
      const after = api.filter((entry) => entry.at >= connectedAt);
      coldExpect(after.length).toBeGreaterThan(0);
      for (const entry of after) {
        coldExpect(entry.auth, entry.url).toBe(`Bearer ${READER_TOKEN}`);
      }
      for (const entry of api) {
        coldExpect(entry.url).not.toContain(READER_TOKEN);
      }
      coldExpect(await page.evaluate(() => localStorage.length)).toBe(0);
      coldExpect(await page.evaluate((key) => sessionStorage.getItem(key), TOKEN_STORAGE_KEY)).toBe(
        null,
      );

      // In memory only: a reload forgets it and asks again.
      await page.reload();
      await coldExpect(page.getByRole("dialog", { name: "Connect to the API" })).toBeVisible();
    },
  );

  cold("Escape closes the dialog and the header button reopens it", async ({ page }) => {
    await page.goto("/spend");
    const dialog = page.getByRole("dialog", { name: "Connect to the API" });
    await coldExpect(dialog).toBeVisible();
    await page.keyboard.press("Escape");
    await coldExpect(dialog).toBeHidden();
    // The header button reopens it, and focus moves into the dialog.
    await page.getByRole("button", { name: "Connect" }).click();
    await coldExpect(dialog).toBeVisible();
    await coldExpect(dialog.getByLabel("API token")).toBeFocused();
  });

  cold("keep for this tab uses sessionStorage; Disconnect clears it", async ({ page }) => {
    await page.goto("/runs");
    const dialog = page.getByRole("dialog", { name: "Connect to the API" });
    await dialog.getByLabel("API token").fill(READER_TOKEN);
    await dialog.getByLabel(/Keep for this tab/).check();
    await dialog.getByRole("button", { name: "Connect" }).click();
    await coldExpect(page.getByRole("button", { name: "Disconnect" })).toBeVisible();
    coldExpect(await page.evaluate((key) => sessionStorage.getItem(key), TOKEN_STORAGE_KEY)).toBe(
      READER_TOKEN,
    );

    // The tab keeps it across a reload without asking.
    await page.reload();
    await coldExpect(page.getByRole("button", { name: "Disconnect" })).toBeVisible();
    await coldExpect(page.getByRole("dialog")).toHaveCount(0);

    await page.getByRole("button", { name: "Disconnect" }).click();
    coldExpect(await page.evaluate((key) => sessionStorage.getItem(key), TOKEN_STORAGE_KEY)).toBe(
      null,
    );
    coldExpect(await page.evaluate(() => localStorage.length)).toBe(0);
    // An explicit Disconnect does not pop the dialog open again.
    await coldExpect(page.getByRole("dialog")).toHaveCount(0);
    await coldExpect(page.getByRole("button", { name: "Connect" })).toBeVisible();
  });

  cold("a refused token reopens the dialog with a message", async ({ page }) => {
    await page.goto("/runs");
    const dialog = page.getByRole("dialog", { name: "Connect to the API" });
    await dialog.getByLabel("API token").fill("not-a-fixture-token-0123456789abcdef");
    await dialog.getByRole("button", { name: "Connect" }).click();
    await coldExpect(dialog).toBeVisible();
    await coldExpect(dialog.getByText("The API refused the token.")).toBeVisible();
  });

  cold("/connect is a linkable home for the dialog (axe clean)", async ({ page }) => {
    await page.goto("/connect");
    await coldExpect(page.getByRole("dialog", { name: "Connect to the API" })).toBeVisible();
    await expectNoSeriousA11yViolations(page);
  });
});

// ---- reader vs operator -----------------------------------------------------------------------

cold.describe("role-aware actions", () => {
  cold("a reader sees the data but no Start run, Approve, Reject or Run now", async ({ page }) => {
    await page.addInitScript(
      ({ key, token }) => {
        sessionStorage.setItem(key, token);
      },
      { key: TOKEN_STORAGE_KEY, token: READER_TOKEN },
    );
    const runId = await startRunViaApi(page.request, "THYAO reader görünürlük testi");

    await page.goto("/runs");
    await coldExpect(page.getByRole("table", { name: /Research runs/ })).toBeVisible();
    const nav = page.getByRole("navigation", { name: "Main" });
    await coldExpect(nav.getByText("Connected (reader)")).toBeVisible();
    await coldExpect(nav.getByRole("link", { name: "New run" })).toHaveCount(0);

    await page.goto("/runs/new");
    await coldExpect(
      page.getByText("Read-only: starting a run needs an operator token"),
    ).toBeVisible();
    await coldExpect(page.getByRole("button", { name: "Start run" })).toHaveCount(0);

    await page.goto("/reconciliation");
    await coldExpect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await coldExpect(page.getByRole("button", { name: "Run now" })).toHaveCount(0);
    await coldExpect(
      page.getByText(/running reconciliation needs an operator token/),
    ).toBeVisible();

    await page.goto(`/runs/${runId}`);
    await coldExpect(page.getByRole("region", { name: /Approval needed/ })).toBeVisible();
    await coldExpect(page.getByRole("button", { name: /^Approve/ })).toHaveCount(0);
    await coldExpect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
    await coldExpect(page.getByText(/deciding approvals needs an operator token/)).toBeVisible();
    await expectNoSeriousA11yViolations(page);

    await page.goto("/approvals");
    await coldExpect(
      page.getByRole("heading", { level: 1, name: "Pending approvals" }),
    ).toBeVisible();
    await coldExpect(page.getByRole("button", { name: /^Approve/ })).toHaveCount(0);
  });

  test("an operator gets the actions", async ({ page }) => {
    await page.goto("/runs");
    const nav = page.getByRole("navigation", { name: "Main" });
    await expect(nav.getByText("Connected (operator)")).toBeVisible();
    await expect(nav.getByRole("link", { name: "New run" })).toBeVisible();
    await page.goto("/reconciliation");
    await expect(page.getByRole("button", { name: "Run now" })).toBeVisible();
  });

  test("a 403 on a write is explained as a read-only token", async ({ page }) => {
    // The operator UI is shown, but the server refuses: the message must say why.
    await page.goto("/runs/new");
    await page.route("**/api/v1/runs", (route) => {
      if (route.request().method() === "POST") {
        return route.fulfill({
          status: 403,
          contentType: "application/problem+json",
          body: JSON.stringify({ status: 403, detail: "Access denied" }),
        });
      }
      return route.fallback();
    });
    await page.getByLabel("Your question").fill("THYAO son açıklamalar neler?");
    await page.getByRole("button", { name: "Start run" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "read-only" })).toBeVisible();
  });
});

// ---- SSE through the fixture server with auth ------------------------------------------------

test("a live run streams over fetch SSE with the Authorization header and no token in the URL", async ({
  page,
}) => {
  const seen: { url: string; auth: string | undefined; accept: string | undefined }[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/events")) {
      void request.allHeaders().then((headers) => {
        seen.push({ url: request.url(), auth: headers.authorization, accept: headers.accept });
      });
    }
  });

  await page.goto("/runs/new");
  await page.getByLabel("Your question").fill("THYAO son açıklamalar neler? (SSE)");
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.getByRole("region", { name: /Approval needed/ })).toBeVisible();
  await page.getByRole("button", { name: /^Approve/ }).click();
  await expect(page.getByRole("heading", { name: "Report" })).toBeVisible({ timeout: 15_000 });

  expect(seen.length).toBeGreaterThan(0);
  for (const entry of seen) {
    expect(entry.url).not.toContain(OPERATOR_TOKEN);
    expect(entry.auth).toBe(`Bearer ${OPERATOR_TOKEN}`);
  }
  expect(seen.some((entry) => entry.accept?.includes("text/event-stream"))).toBe(true);
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
  await expectNoSeriousA11yViolations(page);
});

// ---- daily cap -> recorded demo ----------------------------------------------------------------

test("a 503 daily-cap answer offers the recorded demo, which switches the session to replay", async ({
  page,
}) => {
  // The recording this test switches to is the SAMPLE one; the published one is real.
  await page.route("**/demo/capture.json", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: SAMPLE_CAPTURE }),
  );
  await page.goto("/runs/new");
  await page.getByLabel("Your question").fill("__LLM_CAP__ THYAO son açıklamalar?");
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "daily model budget" })).toBeVisible();
  const offer = page.getByRole("button", { name: "Open the recorded demo" });
  await expect(offer).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  await offer.click();
  await expect(page.getByTestId("replay-banner")).toContainText("Recorded on SAMPLE DATA");
  await expect(page.getByTestId("replay-banner")).toContainText(
    "Base Sepolia testnet. Nothing on this page is live.",
  );
  expect(await page.evaluate(() => sessionStorage.getItem("saiman.mode"))).toBe("replay");
  await page.goto("/runs");
  await expect(page.getByRole("table", { name: /Research runs/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "Connect" })).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);

  await page.getByRole("button", { name: "Back to live" }).click();
  await expect(page.getByTestId("replay-banner")).toHaveCount(0);
});

test("a run that fails on the daily cap offers the recorded demo too", async ({ page }) => {
  await page.goto("/runs/new");
  await page.getByLabel("Your question").fill("__LLM_CAP_MID__ THYAO son açıklamalar?");
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.getByText(/The run failed \(LLM_DAILY_CAP_REACHED\)/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Open the recorded demo" })).toBeVisible();
});
