import { readFileSync } from "node:fs";
import { expect, test as base } from "@playwright/test";

const TOKEN_STORAGE_KEY = "saiman.apiToken";

/**
 * `test` for the live specs: the API requires a Bearer token once auth is on (ADR-0023), so the
 * operator token from `SAIMAN_E2E_TOKEN_FILE` (or `SAIMAN_E2E_TOKEN`) is seeded into this tab's sessionStorage before the app
 * loads (the same path as e2e/helpers.ts). The value is never logged or put in a URL; a missing
 * variable fails with a message that names the variable only.
 */
export const test = base.extend({
  page: async ({ page }, use) => {
    // A file path is preferred (`make e2e-live`): the token then never sits in the environment of the
    // Playwright and browser processes.
    const tokenFile = process.env.SAIMAN_E2E_TOKEN_FILE;
    const token = tokenFile ? readFileSync(tokenFile, "utf8").trim() : process.env.SAIMAN_E2E_TOKEN;
    if (!token) {
      throw new Error(
        "Set SAIMAN_E2E_TOKEN_FILE (path to the operator token file) or SAIMAN_E2E_TOKEN (see web/README.md, Live e2e).",
      );
    }
    await page.addInitScript(
      ({ key, value }) => {
        sessionStorage.setItem(key, value);
      },
      { key: TOKEN_STORAGE_KEY, value: token },
    );
    // eslint-disable-next-line react-hooks/rules-of-hooks -- Playwright's fixture callback, not a React hook
    await use(page);
  },
});

export { expect };
