import { expect, test } from "@playwright/test";

test("landing route shows the System check card", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "System check" })).toBeVisible();
});
