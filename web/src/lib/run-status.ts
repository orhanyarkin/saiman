import type { RunStatus } from "@/lib/api/types";

/** Text plus a glyph per status: state is never conveyed by colour alone. */
export const STATUS_TEXT: Record<RunStatus, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  AWAITING_APPROVAL: "Waiting for your approval",
  SUCCEEDED: "Completed",
  FAILED: "Failed",
};

export const STATUS_GLYPH: Record<RunStatus, string> = {
  QUEUED: "○",
  RUNNING: "▶",
  AWAITING_APPROVAL: "⏸",
  SUCCEEDED: "✓",
  FAILED: "✕",
};
