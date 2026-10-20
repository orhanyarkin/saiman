import "@fontsource-variable/inter";
import { createRootRoute, Link, Outlet } from "@tanstack/react-router";

import { AuthStatus } from "@/components/auth/auth-status";
import { EnvironmentBanner } from "@/components/environment-banner";
import { ReplayBanner } from "@/components/replay/replay-banner";
import { usePendingApprovalCount } from "@/lib/hooks";
import { isReplayMode } from "@/lib/mode";
import { useCapabilities } from "@/lib/use-capabilities";

export const Route = createRootRoute({
  component: RootComponent,
});

/**
 * One nav item. `whitespace-nowrap` keeps an item on one line inside the scrolling row at 390px;
 * the active item is marked by weight and a background as well as `aria-current`, so the state
 * does not rest on colour.
 */
const navLink =
  "block rounded-md px-2 py-1 text-sm font-medium whitespace-nowrap underline-offset-4 transition-colors duration-[var(--dur-fast)] hover:bg-surface-2 hover:underline data-[status=active]:bg-surface-2 data-[status=active]:font-semibold";

function RootComponent() {
  const pending = usePendingApprovalCount();
  const { canOperate } = useCapabilities();
  return (
    <div className="bg-background min-h-screen">
      <a
        href="#main"
        className="bg-primary text-primary-foreground sr-only z-50 rounded-md px-3 py-2 focus:not-sr-only focus:absolute focus:top-2 focus:left-2"
      >
        Skip to main content
      </a>
      <header>
        {isReplayMode ? <ReplayBanner /> : <EnvironmentBanner />}
        {/*
         * One row on a wide screen. At 390px the brand and the auth control keep the first line and
         * the links wrap below it as a single horizontally scrolling line: every item stays in the
         * DOM, in tab order and in the accessibility tree (focus scrolls its item into view), which
         * a hamburger would not. `py-1` inside the scroller leaves room for the focus ring, which
         * an `overflow-x` container would otherwise clip.
         */}
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b px-4 py-3">
          <Link to="/" className="order-1 font-semibold">
            Saiman
          </Link>
          <nav
            aria-label="Main"
            className="order-3 w-full overflow-x-auto sm:order-2 sm:w-auto sm:overflow-x-visible"
          >
            <ul
              className="flex items-center gap-1 py-1"
              // Chromium leaves a partly visible item where it is when it takes focus, so the last
              // items of the scrolling row would be focused half off-screen. "nearest" does
              // nothing when the item is already fully visible and never scrolls the page itself.
              onFocus={(event) => {
                event.target.scrollIntoView?.({ block: "nearest", inline: "nearest" });
              }}
            >
              <li>
                <Link
                  to="/runs"
                  activeOptions={{ exact: true }}
                  className={navLink}
                  activeProps={{ "aria-current": "page" }}
                >
                  Runs
                </Link>
              </li>
              {canOperate ? (
                <li>
                  <Link to="/runs/new" className={navLink} activeProps={{ "aria-current": "page" }}>
                    New run
                  </Link>
                </li>
              ) : null}
              <li>
                <Link to="/approvals" className={navLink} activeProps={{ "aria-current": "page" }}>
                  Approvals
                  {pending !== null && pending > 0 ? (
                    <span
                      data-tone="warning"
                      className="tone-tint t-num ml-1 rounded-full border px-2 text-xs font-semibold"
                    >
                      {pending}
                      <span className="sr-only"> pending</span>
                    </span>
                  ) : null}
                </Link>
              </li>
              <li>
                <Link to="/spend" className={navLink} activeProps={{ "aria-current": "page" }}>
                  Spend
                </Link>
              </li>
              <li>
                <Link to="/ledger" className={navLink} activeProps={{ "aria-current": "page" }}>
                  Ledger
                </Link>
              </li>
              <li>
                <Link
                  to="/reconciliation"
                  className={navLink}
                  activeProps={{ "aria-current": "page" }}
                >
                  Reconciliation
                </Link>
              </li>
              <li>
                <Link to="/revenue" className={navLink} activeProps={{ "aria-current": "page" }}>
                  Revenue
                </Link>
              </li>
            </ul>
          </nav>
          <div className="order-2 ml-auto sm:order-3">
            <AuthStatus />
          </div>
        </div>
      </header>
      <main id="main" tabIndex={-1} className="mx-auto max-w-4xl p-4 outline-none sm:p-8">
        <Outlet />
      </main>
    </div>
  );
}
