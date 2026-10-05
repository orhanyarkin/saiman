import type { RunStatus } from "@/lib/api/types";
import { STATUS_GLYPH, STATUS_TEXT } from "@/lib/run-status";

/** Status as glyph plus words (never colour alone). */
export function StatusBadge({ status }: { status: RunStatus }) {
  return (
    <span className="inline-flex items-center gap-1 rounded border px-2 py-0.5 text-sm font-medium whitespace-nowrap">
      <span aria-hidden="true">{STATUS_GLYPH[status]}</span>
      {STATUS_TEXT[status]}
    </span>
  );
}
