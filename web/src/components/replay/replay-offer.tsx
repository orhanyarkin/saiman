import { Button } from "@/components/ui/button";
import { isReplayMode, switchToReplay } from "@/lib/mode";

/**
 * Offered when the daily model cap blocks live runs: switches this tab to the bundled recording.
 * Nothing in a recording can change state, so the offer is hidden there.
 */
export function ReplayOffer({ text }: { text: string }) {
  if (isReplayMode) {
    return null;
  }
  return (
    <div role="status" className="space-y-2 rounded-md border border-sky-300 bg-sky-50 p-3 text-sm">
      <p>{text}</p>
      <Button type="button" variant="outline" onClick={switchToReplay}>
        Open the recorded demo
      </Button>
    </div>
  );
}
