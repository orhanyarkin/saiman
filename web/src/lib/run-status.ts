import type { RunStatus } from "@/lib/api/types";

/** Text plus a glyph per status: state is never conveyed by colour alone. */
export const STATUS_TEXT: Record<RunStatus, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  AWAITING_APPROVAL: "Waiting for your approval",
  SUCCEEDED: "Completed",
  FAILED: "Failed",
};

/**
 * The status token each state is tinted with (`data-tone` in index.css, the ui-taste tint recipe).
 * Queued is neutral: nothing has happened yet, so it carries no status colour. Colour is the third
 * carrier here, after the word and the glyph, never the only one.
 */
export const STATUS_TONE: Record<RunStatus, "neutral" | "info" | "warning" | "success" | "danger"> =
  {
    QUEUED: "neutral",
    RUNNING: "info",
    AWAITING_APPROVAL: "warning",
    SUCCEEDED: "success",
    FAILED: "danger",
  };

export const STATUS_GLYPH: Record<RunStatus, string> = {
  QUEUED: "○",
  RUNNING: "▶",
  AWAITING_APPROVAL: "⏸",
  SUCCEEDED: "✓",
  FAILED: "✕",
};
