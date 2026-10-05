import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { mergeEvents, type RunEvent } from "@/lib/api/run-events";
import { queryKeys, runEventsExportQuery, runSummaryQuery } from "@/lib/api/queries";
import { subscribeRunEvents } from "@/lib/api/source";
import { isTerminalStatus } from "@/lib/api/types";

/** Re-renders every `intervalMs` with the current time (visual countdowns only). */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => {
      setNow(Date.now());
    }, intervalMs);
    return () => {
      clearInterval(id);
    };
  }, [intervalMs]);
  return now;
}

/** Returns `value` but updates at most once per `delayMs` (keeps live regions calm). */
export function useThrottledValue<T>(value: T, delayMs = 2000): T {
  const [shown, setShown] = useState(value);
  useEffect(() => {
    if (Object.is(shown, value)) {
      return;
    }
    const id = setTimeout(() => {
      setShown(value);
    }, delayMs);
    return () => {
      clearTimeout(id);
    };
  }, [value, shown, delayMs]);
  return shown;
}

/** Sets `document.title` to `<title> · Saiman` while the page is mounted. */
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    const previous = document.title;
    document.title = `${title} · Saiman`;
    return () => {
      document.title = previous;
    };
  }, [title]);
}

export type StreamState = "idle" | "live" | "reconnecting" | "closed";

/**
 * Events of one run. A live run is followed over SSE (the browser resumes with `Last-Event-ID`);
 * once the summary says the run is over, the ordered JSON export is read once and is authoritative.
 * Both are merged by `seq`, so a handover between them never duplicates or loses an event.
 * Mount the component that calls this with `key={runId}` so state never leaks between runs.
 */
export function useRunEvents(runId: string) {
  const queryClient = useQueryClient();
  const summary = useQuery(runSummaryQuery(runId));
  const terminal = summary.data ? isTerminalStatus(summary.data.status) : false;
  const shouldStream = summary.isSuccess && !terminal;

  const exported = useQuery({ ...runEventsExportQuery(runId), enabled: terminal });
  const [live, setLive] = useState<RunEvent[]>([]);
  const [stream, setStream] = useState<StreamState>("idle");

  useEffect(() => {
    if (!shouldStream) {
      return;
    }
    const subscription = subscribeRunEvents(
      runId,
      (event) => {
        setLive((previous) => mergeEvents(previous, [event]));
        setStream("live");
        if (event.type.startsWith("PAYMENT_") || event.type.startsWith("RUN_")) {
          void queryClient.invalidateQueries({ queryKey: queryKeys.runSummary(runId) });
        }
        if (event.type === "RUN_COMPLETED" || event.type === "RUN_FAILED") {
          // T3c: the ledger rows for this run's payments become visible after the terminal event.
          void queryClient.invalidateQueries({ queryKey: ["ledger"] });
        }
      },
      {
        onClose: () => {
          setStream("closed");
          void queryClient.invalidateQueries({ queryKey: queryKeys.runSummary(runId) });
        },
        onReconnecting: () => {
          setStream("reconnecting");
        },
      },
    );
    return () => {
      subscription.close();
    };
  }, [runId, shouldStream, queryClient]);

  const events = mergeEvents(live, exported.data ?? []);
  return { events, summary, exported, stream, terminal };
}
