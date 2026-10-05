import { defineConfig, devices } from "@playwright/test";

/**
 * Run only via `pnpm e2e`. Never invoked by `pnpm test`, `pnpm lint` or `pnpm build`. Playwright's
 * Chromium must be installed once (`pnpm exec playwright install chromium`).
 *
 * Fixture mode: two web servers. The fixture server (e2e/fixture-server.ts) stands in for the
 * orchestrator; Vite serves the app and proxies /api to it (SAIMAN_API_TARGET). The ports differ
 * from the dev defaults so a running `pnpm dev` or `make up` never gets mixed up with the tests.
 */
const FIXTURE_PORT = Number(process.env.SAIMAN_FIXTURE_PORT ?? 4010);
const APP_PORT = Number(process.env.SAIMAN_E2E_APP_PORT ?? 5174);
const REPLAY_PORT = Number(process.env.SAIMAN_E2E_REPLAY_PORT ?? 5175);

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  testIgnore: "**/live/**", // the live acceptance spec runs via `pnpm e2e:live` against a real stack
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: "list",
  use: {
    baseURL: `http://localhost:${String(APP_PORT)}`,
    trace: "on-first-retry",
  },
  webServer: [
    {
      command: "node e2e/fixture-server.ts",
      url: `http://127.0.0.1:${String(FIXTURE_PORT)}/api/v1/ping`,
      // Auth is on (the default): every spec connects with a fixture token (e2e/helpers.ts).
      env: { SAIMAN_FIXTURE_PORT: String(FIXTURE_PORT), SAIMAN_STEP_MS: "100" },
      reuseExistingServer: false,
      gracefulShutdown: { signal: "SIGTERM", timeout: 2000 },
    },
    {
      command: `pnpm exec vite --port ${String(APP_PORT)} --strictPort`,
      url: `http://localhost:${String(APP_PORT)}`,
      env: {
        SAIMAN_API_TARGET: `http://127.0.0.1:${String(FIXTURE_PORT)}`,
        VITE_OTEL_ENABLED: "false",
      },
      reuseExistingServer: false,
      gracefulShutdown: { signal: "SIGTERM", timeout: 2000 },
    },
    {
      // The static replay build (ADR-0026): built with VITE_DEMO_MODE=replay and served as files,
      // no API behind it. replay.spec.ts feeds it the SAMPLE fixture; replay-real.spec.ts reads the real /demo/capture.json.
      command: `pnpm exec vite build --outDir dist-replay --emptyOutDir --logLevel warn && pnpm exec vite preview --outDir dist-replay --port ${String(REPLAY_PORT)} --strictPort`,
      url: `http://localhost:${String(REPLAY_PORT)}`,
      env: { VITE_DEMO_MODE: "replay", VITE_OTEL_ENABLED: "false" },
      reuseExistingServer: false,
      timeout: 120_000,
      gracefulShutdown: { signal: "SIGTERM", timeout: 2000 },
    },
  ],
  projects: [
    {
      name: "chromium",
      testIgnore: ["**/live/**", "**/replay*.spec.ts"],
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "replay",
      testMatch: "**/replay*.spec.ts",
      use: { ...devices["Desktop Chrome"], baseURL: `http://localhost:${String(REPLAY_PORT)}` },
    },
  ],
});
