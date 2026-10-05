import { expect, test as base } from "@playwright/test";

const TOKEN_STORAGE_KEY = "saiman.apiToken";

/**
 * `test` for the live specs: the API requires a Bearer token once auth is on (ADR-0023), so the
 * operator token from `SAIMAN_E2E_TOKEN` is seeded into this tab's sessionStorage before the app
 * loads (the same path as e2e/helpers.ts). The value is never logged or put in a URL; a missing
 * variable fails with a message that names the variable only.
 */
export const test = base.extend({
  page: async ({ page }, use) => {
    const token = process.env.SAIMAN_E2E_TOKEN;
    if (!token) {
      throw new Error(
        "SAIMAN_E2E_TOKEN is not set: export the operator API token (see web/README.md, Live e2e).",
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
