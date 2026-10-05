import type { RunAnnotation } from "@/lib/api/replay";

const TONE_CLASS: Record<RunAnnotation["tone"], string> = {
  warning: "border-amber-700 bg-amber-50 text-amber-950",
  info: "border-sky-700 bg-sky-50 text-sky-950",
};

/**
 * A note attached to a recorded run. Plain text only: React escapes the strings and there are no
 * links. Fixed light colours with their own text colour keep contrast independent of the theme.
 */
export function AnnotationCallout({
  annotation,
  compact = false,
}: {
  annotation: RunAnnotation;
  compact?: boolean;
}) {
  return (
    <div
      role="note"
      data-testid="run-annotation"
      className={`rounded border-2 px-3 py-2 text-sm ${TONE_CLASS[annotation.tone]}`}
    >
      <p className="font-semibold">
        <span aria-hidden="true">{annotation.tone === "warning" ? "⚠ " : "ℹ "}</span>
        {annotation.label}
      </p>
      <p className={compact ? "line-clamp-3" : undefined}>{annotation.detail}</p>
    </div>
  );
}
