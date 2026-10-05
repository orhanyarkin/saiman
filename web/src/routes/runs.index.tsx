import { useInfiniteQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { RunsTable } from "@/components/run/runs-table";
import { Button, buttonVariants } from "@/components/ui/button";
import { runListQuery } from "@/lib/api/queries";
import { isReplayMode } from "@/lib/api/source";
import { useDocumentTitle } from "@/lib/hooks";

export const Route = createFileRoute("/runs/")({
  component: RunsPage,
});

function RunsPage() {
  useDocumentTitle("Runs");
  const runs = useInfiniteQuery(runListQuery());
  const pageError: unknown = runs.isFetchNextPageError ? runs.error : null;
  const items = runs.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Research runs</h1>

      {runs.isPending ? (
        <p role="status" aria-busy="true">
          Loading runs…
        </p>
      ) : runs.isError ? (
        <div className="space-y-3">
          <ErrorNotice error={runs.error} />
          <Button
            variant="outline"
            onClick={() => {
              void runs.refetch();
            }}
          >
            Try again
          </Button>
        </div>
      ) : items.length === 0 ? (
        <div className="space-y-3">
          <p className="text-muted-foreground">No runs yet.</p>
          {isReplayMode ? null : (
            <Link to="/runs/new" className={buttonVariants()}>
              Start your first research run
            </Link>
          )}
        </div>
      ) : (
        <>
          <RunsTable runs={items} caption="Research runs, newest first" />
          <p role="status" className="text-muted-foreground text-sm">
            Showing {items.length} run{items.length === 1 ? "" : "s"}.
          </p>
          {pageError === null ? null : <ErrorNotice error={pageError} />}
          {runs.hasNextPage ? (
            <Button
              variant="outline"
              disabled={runs.isFetchingNextPage}
              onClick={() => {
                void runs.fetchNextPage();
              }}
            >
              {runs.isFetchingNextPage ? "Loading…" : "Load more"}
            </Button>
          ) : null}
        </>
      )}
    </div>
  );
}
