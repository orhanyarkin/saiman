import { useQuery } from "@tanstack/react-query";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { fetchPing } from "@/lib/api/ping";

/** Local Jaeger UI (ADR-0006); the trace query API lives behind the same host. */
const JAEGER_TRACE_BASE_URL = "http://localhost:16686/trace";

/**
 * Calls GET /api/v1/ping and shows the result: proof that the browser can reach the
 * orchestrator and that the request produced one distributed trace through Postgres.
 */
export function SystemCheckCard() {
  const { data, error, isPending, isError, isFetching, refetch } = useQuery({
    queryKey: ["ping"],
    queryFn: fetchPing,
  });

  return (
    <Card className="w-full max-w-md">
      <CardHeader>
        <CardTitle>
          <h2>System check</h2>
        </CardTitle>
        <CardDescription>
          Confirms the browser can reach the orchestrator and Postgres.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {isPending ? (
          <p role="status">Checking orchestrator…</p>
        ) : isError ? (
          <p role="alert" className="text-destructive">
            {error instanceof Error ? error.message : "Could not reach the orchestrator."}
          </p>
        ) : (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
            <dt className="text-muted-foreground font-medium">Service</dt>
            <dd>{data.service}</dd>
            <dt className="text-muted-foreground font-medium">DB time</dt>
            <dd>{data.dbTime}</dd>
            <dt className="text-muted-foreground font-medium">Trace</dt>
            <dd>
              {data.traceId ? (
                <a
                  className="underline underline-offset-4 hover:text-primary"
                  href={`${JAEGER_TRACE_BASE_URL}/${encodeURIComponent(data.traceId)}`}
                  target="_blank"
                  rel="noreferrer"
                  aria-label={`Open trace ${data.traceId} in Jaeger (new tab)`}
                >
                  {data.traceId}
                </a>
              ) : (
                <span className="text-muted-foreground">not available</span>
              )}
            </dd>
          </dl>
        )}
      </CardContent>
      <CardFooter>
        <Button onClick={() => void refetch()} disabled={isFetching}>
          {isFetching ? "Checking…" : "Check again"}
        </Button>
      </CardFooter>
    </Card>
  );
}
