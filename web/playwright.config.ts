import { defineConfig, devices } from "@playwright/test";

/**
 * Run only via `pnpm e2e`. Never invoked by `pnpm test`, `pnpm lint` or `pnpm build`, and
 * Playwright browsers are not installed by this task — see the frontend engineer's task report
 * for the exact `playwright install` command to run once, locally.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: "list",
  use: {
    baseURL: "http://localhost:5173",
    trace: "on-first-retry",
  },
  webServer: {
    command: "pnpm dev",
    url: "http://localhost:5173",
    reuseExistingServer: !process.env.CI,
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
