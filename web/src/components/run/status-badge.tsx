import type { RunStatus } from "@/lib/api/types";
import { STATUS_GLYPH, STATUS_TEXT, STATUS_TONE } from "@/lib/run-status";

/**
 * Status as glyph plus words plus its status tint, the same pill wherever a run status appears.
 * The glyph and the word carry the state on their own; the tint only makes it findable in a list.
 */
export function StatusBadge({ status }: { status: RunStatus }) {
  return (
    <span
      data-tone={STATUS_TONE[status]}
      className="tone-tint inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-sm font-medium whitespace-nowrap"
    >
      <span aria-hidden="true">{STATUS_GLYPH[status]}</span>
      {STATUS_TEXT[status]}
    </span>
  );
}
