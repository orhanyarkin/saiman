import { defineConfig } from "@playwright/test";

/**
 * Screenshot capture against a running stack (`make up`, then `make ui-shots`), for the before and
 * after sets of the UI refresh (docs/design/ui-refresh.md). No fixture server and no web server:
 * nginx on :8088 serves the built app and proxies /api to the real services, exactly like
 * playwright.live.config.ts.
 *
 * Tracing, video and HAR are off on purpose, and the reporter is `list` rather than `html`: a trace
 * or a HAR embeds the Authorization header, and the HTML report embeds the trace. The operator
 * token must not reach any artefact on disk (ADR-0023).
 *
 * Run only via `pnpm ui:shots`; the normal `pnpm e2e` ignores e2e/shots/.
 */
const BASE_URL = process.env.SAIMAN_E2E_BASE_URL ?? "http://localhost:8088";

export default defineConfig({
  testDir: "./e2e/shots",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "list",
  outputDir: "test-results/ui-shots", // gitignored; empty in practice, nothing is attached
  timeout: 120_000,
  use: {
    baseURL: BASE_URL,
    locale: "en-GB",
    timezoneId: "UTC",
    trace: "off",
    video: "off",
    screenshot: "off", // the spec writes its own files; no automatic failure screenshots
  },
  projects: [
    {
      name: "desktop",
      use: { viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 },
    },
    {
      name: "mobile",
      use: { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 },
    },
  ],
});
