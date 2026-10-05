import type { RunEvent } from "@/lib/api/run-events";
import { describeEvent } from "@/lib/run-view-model";

export function Timeline({ events }: { events: readonly RunEvent[] }) {
  if (events.length === 0) {
    return <p className="text-muted-foreground text-sm">No events yet.</p>;
  }
  return (
    <ol aria-label="Event timeline" className="space-y-2">
      {events.map((event) => {
        const item = describeEvent(event);
        return (
          <li key={event.seq} className="border-l-2 pl-3 text-sm">
            <span className="font-medium">{item.label}</span>{" "}
            <time dateTime={item.at} className="text-muted-foreground text-xs">
              {new Date(item.at).toLocaleTimeString()}
            </time>
            {item.detail ? (
              <span className="text-muted-foreground block">{item.detail}</span>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}
