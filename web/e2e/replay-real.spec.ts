import { expect, test } from "@playwright/test";

import { expectNoSeriousA11yViolations } from "./helpers";

/**
 * The REAL published recording (public/demo/capture.json), served by the static replay build
 * with no interception. Ids are the ones in that file; update them when it is re-captured.
 */
const FAILED_RUN = "4feeba86-8601-4797-be23-960a2908e8ff";
const OK_RUN = "d45effc1-4b59-40b0-93a1-08116a7868e2";

test("the published recording shows the frozen KAP snapshot in the banner", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("replay-banner")).toContainText(
    "frozen KAP snapshot: disclosures up to 29 Dec 2023",
  );
  await expect(page.getByTestId("replay-banner")).toContainText("local docker compose (WSL2)");
  await expectNoSeriousA11yViolations(page);
});

test("the runs list lists the three recorded runs, the failed one with its note", async ({
  page,
}) => {
  await page.goto("/runs");
  await expect(page.getByRole("row").filter({ hasText: "ARCLK" })).toBeVisible();
  await expect(page.getByRole("row").filter({ hasText: "THYAO" })).toBeVisible();
  const failed = page.getByRole("row").filter({ hasText: "ASELS" });
  await expect(failed.getByTestId("run-annotation")).toContainText("Failed run");
  await expectNoSeriousA11yViolations(page);
});

test("the failed run page shows its callout; a successful run has none", async ({ page }) => {
  await page.goto(`/runs/${FAILED_RUN}`);
  const note = page.getByTestId("run-annotation");
  await expect(note).toBeVisible();
  await expect(note).toContainText("Failed run: payment not completed, no answer");
  await expect(note.getByRole("link")).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);

  await page.goto(`/runs/${OK_RUN}`);
  await expect(page.getByRole("heading", { name: "Report" })).toBeVisible();
  await expect(page.getByTestId("run-annotation")).toHaveCount(0);
});
