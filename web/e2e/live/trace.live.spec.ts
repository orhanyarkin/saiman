import { expect, test } from "./live-test";

/**
 * M0 acceptance, automated: the dashboard's "System check" produces a trace that spans the browser,
 * the orchestrator and Postgres, and Jaeger has it. Needs `make up` (the build has browser tracing
 * on, same-origin /otlp) and Jaeger on :16686 (override with SAIMAN_JAEGER_URL). Costs nothing.
 */
const JAEGER = process.env.SAIMAN_JAEGER_URL ?? "http://localhost:16686";

test("the system check trace spans web, orchestrator and Postgres", async ({ page }) => {
  await page.goto("/");
  const link = page.locator('a[aria-label^="Open trace"]').first();
  await expect(link).toBeVisible({ timeout: 20_000 });
  const traceId = (await link.innerText()).trim();
  expect(traceId).toMatch(/^[0-9a-f]{32}$/);

  // The browser exports spans in batches (about every 5 s); poll Jaeger until all three show up.
  await expect
    .poll(
      async () => {
        const response = await fetch(`${JAEGER}/api/v3/traces/${traceId}`);
        if (!response.ok) return "";
        return await response.text();
      },
      { timeout: 60_000, intervals: [2_000] },
    )
    .toMatch(/saiman-web/);

  const body = await (await fetch(`${JAEGER}/api/v3/traces/${traceId}`)).text();
  expect(body).toContain("orchestrator");
  expect(body.toLowerCase()).toMatch(/jdbc|postgres|db\.system/);
});
