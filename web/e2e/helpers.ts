import AxeBuilder from "@axe-core/playwright";
import { expect, test as base, type Page } from "@playwright/test";

/** Fixture-server tokens (e2e/fixture-server.ts): any `fixture-<role>-...` is accepted. */
export const READER_TOKEN = "fixture-reader-0123456789abcdef0123456789abcdef";
export const OPERATOR_TOKEN = "fixture-operator-0123456789abcdef0123456789abcdef";
export const TOKEN_STORAGE_KEY = "saiman.apiToken";

/**
 * `test` whose pages start already connected as an operator: the token is placed in this tab's
 * sessionStorage (the "keep for this tab" path of ADR-0023) before the app loads. Specs of the
 * token flow itself import `test` from `@playwright/test` and start cold.
 */
export const test = base.extend({
  page: async ({ page }, use) => {
    await page.addInitScript(
      ({ key, token }) => {
        sessionStorage.setItem(key, token);
      },
      { key: TOKEN_STORAGE_KEY, token: OPERATOR_TOKEN },
    );
    // eslint-disable-next-line react-hooks/rules-of-hooks -- Playwright's fixture callback, not a React hook
    await use(page);
  },
});

export { expect };

/** Serious and critical axe violations fail the test; the rest is reported by axe only. */
export async function expectNoSeriousA11yViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  const blocking = results.violations.filter(
    (v) => v.impact === "serious" || v.impact === "critical",
  );
  expect(blocking.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}
