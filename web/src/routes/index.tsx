import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { RunsTable } from "@/components/run/runs-table";
import { SystemCheckCard } from "@/components/system-check-card";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { recentRunsQuery } from "@/lib/api/queries";
import { useDocumentTitle } from "@/lib/hooks";

export const Route = createFileRoute("/")({
  component: Index,
});

function RecentRuns() {
  const recent = useQuery(recentRunsQuery());
  if (recent.isPending) {
    return (
      <p role="status" aria-busy="true" className="text-muted-foreground text-sm">
        Loading recent runs…
      </p>
    );
  }
  if (recent.isError) {
    return <ErrorNotice error={recent.error} />;
  }
  if (recent.data.items.length === 0) {
    return (
      <div className="space-y-3">
        <p className="text-muted-foreground text-sm">
          No runs to show yet. Start a run and it will be listed here.
        </p>
        <Link to="/runs/new" className={buttonVariants({ variant: "outline" })}>
          Start your first run
        </Link>
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <RunsTable runs={recent.data.items} caption="The five most recent research runs" />
      <Link to="/runs" className="text-sm underline underline-offset-4">
        See all runs
      </Link>
    </div>
  );
}

function Index() {
  useDocumentTitle("Home");
  return (
    <div className="space-y-8">
      <section aria-labelledby="intro-title" className="space-y-4">
        <h1 id="intro-title" className="text-3xl font-semibold">
          Saiman, the AI treasurer for research agents
        </h1>
        <p className="max-w-prose">
          Research agents answer questions about Turkish capital-markets disclosures and pay for
          each data call over x402 (HTTP 402 plus testnet stablecoin). Every payment passes
          deterministic spend limits that no prompt can change, and lands in a double-entry ledger
          that is reconciled against the chain.
        </p>
        <Link to="/runs/new" className={buttonVariants({ size: "lg" })}>
          Start a research run
        </Link>
      </section>

      <section aria-labelledby="status-title" className="space-y-3">
        <h2 id="status-title" className="text-xl font-semibold">
          System status
        </h2>
        <SystemCheckCard />
      </section>

      <section aria-labelledby="recent-title">
        <Card>
          <CardHeader>
            <CardTitle>
              <h2 id="recent-title">Recent runs</h2>
            </CardTitle>
            <CardDescription>Your five latest research runs.</CardDescription>
          </CardHeader>
          <CardContent>
            <RecentRuns />
          </CardContent>
        </Card>
      </section>
    </div>
  );
}
