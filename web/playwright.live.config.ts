import { defineConfig, devices } from "@playwright/test";

/**
 * Live acceptance against a running stack (`make up`, then `make e2e-live`). No fixture server and
 * no web server: nginx on :8088 serves the built app and proxies /api to the real services.
 * Run only via `pnpm e2e:live`; the normal `pnpm e2e` ignores e2e/live/.
 */
const BASE_URL = process.env.SAIMAN_E2E_BASE_URL ?? "http://localhost:8088";

export default defineConfig({
  testDir: "./e2e/live",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0, // a real run spends (testnet) money: never retry silently
  reporter: "list",
  timeout: 10 * 60_000, // a payment above the threshold waits for a human, up to five minutes
  use: { baseURL: BASE_URL, trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
