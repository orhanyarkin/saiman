import type { RunEvent } from "@/lib/api/run-events";
import { formatTime } from "@/lib/day";
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
            <time dateTime={item.at} title={item.at} className="text-muted-foreground text-xs">
              {formatTime(item.at)}
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
