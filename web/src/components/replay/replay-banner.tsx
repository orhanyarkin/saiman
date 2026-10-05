import { bannerText } from "@/lib/banner-text";
import { getLoadedCapture } from "@/lib/api/replay";
import { isReplayBuild, isReplayMode, leaveReplay } from "@/lib/mode";

/** Shown on every route in replay mode (ADR-0026). Renders nothing in a live session. */
export function ReplayBanner() {
  if (!isReplayMode) {
    return null;
  }
  return (
    <div
      role="note"
      data-testid="replay-banner"
      className="flex flex-wrap items-center justify-center gap-3 bg-sky-100 px-4 py-2 text-center text-sm font-medium text-sky-950"
    >
      <span>{bannerText(getLoadedCapture())}</span>
      {isReplayBuild ? null : (
        <button type="button" className="underline underline-offset-4" onClick={leaveReplay}>
          Back to live
        </button>
      )}
    </div>
  );
}
