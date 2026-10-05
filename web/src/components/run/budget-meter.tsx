import type { RunSummary } from "@/lib/api/types";
import { formatMoney, moneyTitle } from "@/lib/money";

/** Native `<meter>` for the bar (a presentation ratio only); exact amounts are formatted text. */
export function BudgetMeter({ summary }: { summary: RunSummary }) {
  const { budget, committed, reserved } = summary;
  const usable = Number.isSafeInteger(budget.atomicUnits) && budget.atomicUnits > 0;
  const used = Number.isSafeInteger(committed.atomicUnits) ? committed.atomicUnits : 0;
  return (
    <div>
      <p className="text-sm">
        <span title={moneyTitle(committed)}>Committed {formatMoney(committed)}</span>
        {" · "}
        <span title={moneyTitle(reserved)}>Reserved {formatMoney(reserved)}</span>
        {" of "}
        <span title={moneyTitle(budget)}>{formatMoney(budget)} budget</span>
      </p>
      {usable ? (
        <meter
          className="mt-1 h-3 w-full"
          min={0}
          max={budget.atomicUnits}
          value={Math.min(used, budget.atomicUnits)}
          aria-label="Budget committed"
        />
      ) : null}
    </div>
  );
}
