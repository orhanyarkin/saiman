import { annotationFor, type RunAnnotation } from "@/lib/api/replay";
import { isReplayMode } from "@/lib/mode";

/** The recording's note for a run, or null (always null in a live session). */
export function runAnnotation(runId: string): RunAnnotation | null {
  return isReplayMode ? annotationFor(runId) : null;
}
