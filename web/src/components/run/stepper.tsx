import { RUN_STEPS } from "@/lib/api/run-events";
import { stepLabel, type StepStatus } from "@/lib/run-view-model";
import type { RunStep } from "@/lib/api/run-events";

const STATUS_TEXT: Record<StepStatus, string> = {
  pending: "waiting",
  running: "in progress",
  done: "done",
};
const STATUS_GLYPH: Record<StepStatus, string> = { pending: "○", running: "◔", done: "✓" };

/** Status is conveyed by a glyph and a text label, never by colour alone. */
export function Stepper({ steps }: { steps: Record<RunStep, StepStatus> }) {
  return (
    <ol aria-label="Run steps" className="flex flex-wrap gap-3">
      {RUN_STEPS.map((step, index) => {
        const status = steps[step];
        return (
          <li
            key={step}
            aria-current={status === "running" ? "step" : undefined}
            className={`rounded-md border px-3 py-2 text-sm ${status === "running" ? "border-foreground border-2 font-semibold" : ""}`}
          >
            <span aria-hidden="true">{STATUS_GLYPH[status]} </span>
            <span>
              {String(index + 1)}. {stepLabel(step)}
            </span>
            <span className="text-muted-foreground block text-xs">{STATUS_TEXT[status]}</span>
          </li>
        );
      })}
    </ol>
  );
}
