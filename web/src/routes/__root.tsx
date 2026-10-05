import { createRootRoute, Link, Outlet } from "@tanstack/react-router";

import { isReplayMode } from "@/lib/api/source";
import { usePendingApprovalCount } from "@/lib/hooks";

export const Route = createRootRoute({
  component: RootComponent,
});

const navLink =
  "rounded-md px-2 py-1 text-sm font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2";

function RootComponent() {
  const pending = usePendingApprovalCount();
  return (
    <div className="bg-background min-h-screen">
      <a
        href="#main"
        className="bg-primary text-primary-foreground sr-only z-50 rounded-md px-3 py-2 focus:not-sr-only focus:absolute focus:top-2 focus:left-2"
      >
        Skip to main content
      </a>
      <header>
        <div
          role="note"
          className="bg-amber-100 px-4 py-2 text-center text-sm font-medium text-amber-950"
        >
          Testnet only {"—"} local demo. No real money moves.
          {isReplayMode ? " Recorded run: actions are disabled." : ""}
        </div>
        <nav aria-label="Main" className="flex items-center gap-4 border-b px-4 py-3">
          <Link to="/" className="font-semibold">
            Saiman
          </Link>
          <Link
            to="/runs"
            activeOptions={{ exact: true }}
            className={navLink}
            activeProps={{ "aria-current": "page" }}
          >
            Runs
          </Link>
          <Link to="/runs/new" className={navLink} activeProps={{ "aria-current": "page" }}>
            New run
          </Link>
          <Link to="/approvals" className={navLink} activeProps={{ "aria-current": "page" }}>
            Approvals
            {pending !== null && pending > 0 ? (
              <span className="ml-1 rounded-full border border-amber-600 bg-amber-100 px-2 text-xs font-semibold text-amber-950">
                {pending}
                <span className="sr-only"> pending</span>
              </span>
            ) : null}
          </Link>
          <Link to="/spend" className={navLink} activeProps={{ "aria-current": "page" }}>
            Spend
          </Link>
        </nav>
      </header>
      <main id="main" tabIndex={-1} className="mx-auto max-w-4xl p-4 outline-none sm:p-8">
        <Outlet />
      </main>
    </div>
  );
}
