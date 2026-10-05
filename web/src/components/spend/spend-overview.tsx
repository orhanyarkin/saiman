import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { LlmDay, Money, SpendOverview } from "@/lib/api/types";
import {
  formatMoney,
  formatUsdMicros,
  moneyTitle,
  remainingMoney,
  remainingUsdMicros,
  UNAVAILABLE,
} from "@/lib/money";

/**
 * Below this much model budget left, a new run may be refused with the daily-cap answer. The API
 * does not expose the per-run model budget yet, so this is an approximation shown as a hint only
 * (the 503 itself stays the authority).
 */
const LLM_RUN_BUDGET_HINT_USD_MICROS = 100_000;

/** The shared model (LLM) budget of the UTC day, spent / cap at micro-dollar precision. */
export function LlmDayCard({ llmDay }: { llmDay: LlmDay }) {
  const spent = formatUsdMicros(llmDay.spentUsdMicros);
  const cap = formatUsdMicros(llmDay.capUsdMicros);
  const left = remainingUsdMicros(llmDay.capUsdMicros, llmDay.spentUsdMicros);
  const low = left !== null && left < LLM_RUN_BUDGET_HINT_USD_MICROS;
  const canMeter = Number.isSafeInteger(llmDay.capUsdMicros) && llmDay.capUsdMicros > 0;
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h3>Model budget today (shared)</h3>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-xl font-semibold">
          {spent ?? UNAVAILABLE} <span className="text-muted-foreground text-base">of</span>{" "}
          {cap ?? UNAVAILABLE}
        </p>
        {canMeter ? (
          <meter
            className="mt-2 h-3 w-full"
            min={0}
            max={llmDay.capUsdMicros}
            value={Math.min(Math.max(llmDay.spentUsdMicros, 0), llmDay.capUsdMicros)}
            aria-label="Model budget used today"
          />
        ) : null}
        <p className="text-muted-foreground text-sm">
          One language-model budget for all visitors, per UTC day.
        </p>
        {low ? (
          <p role="note" className="mt-2 text-sm font-medium">
            Not enough is left for a full run: new runs will be offered the recorded demo until
            00:00 UTC.
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

function Amount({ money }: { money: Money }) {
  return <span title={moneyTitle(money)}>{formatMoney(money)}</span>;
}

/** A presentation ratio only (never money math): `<meter>` of `value` against `max`. */
function Meter({ label, value, max }: { label: string; value: Money; max: Money }) {
  if (!Number.isSafeInteger(max.atomicUnits) || max.atomicUnits <= 0) {
    return null;
  }
  const used = Number.isSafeInteger(value.atomicUnits) ? value.atomicUnits : 0;
  return (
    <meter
      className="mt-2 h-3 w-full"
      min={0}
      max={max.atomicUnits}
      value={Math.min(used, max.atomicUnits)}
      aria-label={label}
    />
  );
}

export function SpendOverviewView({ spend }: { spend: SpendOverview }) {
  const { dailyCap, dayReserved, dayCommitted, limits } = spend;
  const remaining = remainingMoney(dailyCap, dayReserved, dayCommitted);
  return (
    <div className="space-y-6">
      <section aria-labelledby="day-title" className="space-y-3">
        <h2 id="day-title" className="text-lg font-semibold">
          Daily budget for {spend.day} (UTC)
        </h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>
                <h3>Daily cap</h3>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-xl font-semibold">
                <Amount money={dailyCap} />
              </p>
              <p className="text-muted-foreground text-sm">Most Saiman may spend in one UTC day.</p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>
                <h3>Remaining</h3>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-xl font-semibold">
                {remaining ? <Amount money={remaining} /> : UNAVAILABLE}
              </p>
              <p className="text-muted-foreground text-sm">Cap minus reserved and committed.</p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>
                <h3>Reserved</h3>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-xl font-semibold">
                <Amount money={dayReserved} />
              </p>
              <Meter label="Reserved of daily cap" value={dayReserved} max={dailyCap} />
              <p className="text-muted-foreground text-sm">Held for payments still in flight.</p>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>
                <h3>Committed</h3>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-xl font-semibold">
                <Amount money={dayCommitted} />
              </p>
              <Meter label="Committed of daily cap" value={dayCommitted} max={dailyCap} />
              <p className="text-muted-foreground text-sm">Paid and settled today.</p>
            </CardContent>
          </Card>
          {/* Recordings made before ADR-0026 have no llmDay. */}
          {(spend as Partial<SpendOverview>).llmDay ? <LlmDayCard llmDay={spend.llmDay} /> : null}
        </div>
      </section>

      <section aria-labelledby="limits-title" className="space-y-3">
        <h2 id="limits-title" className="text-lg font-semibold">
          Limits
        </h2>
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
          <dt className="text-muted-foreground font-medium">Default budget per run</dt>
          <dd>
            <Amount money={limits.defaultRunBudget} />
          </dd>
          <dt className="text-muted-foreground font-medium">Maximum budget per run</dt>
          <dd>
            <Amount money={limits.maxRunBudget} />
          </dd>
          <dt className="text-muted-foreground font-medium">Approval needed above</dt>
          <dd>
            <Amount money={limits.approvalThreshold} />
          </dd>
          <dt className="text-muted-foreground font-medium">Maximum per request</dt>
          <dd>
            {limits.perRequestMax ? <Amount money={limits.perRequestMax} /> : "No separate limit"}
          </dd>
        </dl>
        <p className="max-w-prose text-sm">
          These limits are enforced by ordinary code in the spend-control service, outside the
          language model. No prompt, however clever, can raise a budget or skip an approval.
        </p>
      </section>

      <section aria-labelledby="tools-title" className="space-y-3">
        <h2 id="tools-title" className="text-lg font-semibold">
          Spend by tool
        </h2>
        {spend.byTool.length === 0 ? (
          <p className="text-muted-foreground text-sm">No payments on this day.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Payments on {spend.day} by tool and status</caption>
              <thead>
                <tr>
                  {["Tool", "Status", "Payments", "Amount"].map((heading) => (
                    <th key={heading} scope="col" className="py-1 pr-4">
                      {heading}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {spend.byTool.map((row) => (
                  <tr key={`${row.tool}/${row.status}`} className="border-t">
                    <th scope="row" className="py-1 pr-4 font-normal">
                      {row.tool}
                    </th>
                    <td className="py-1 pr-4">{row.status}</td>
                    <td className="py-1 pr-4">{row.count}</td>
                    <td className="py-1">
                      <Amount money={row.amount} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
