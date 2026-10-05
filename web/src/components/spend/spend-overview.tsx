import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { Money, SpendOverview } from "@/lib/api/types";
import { formatMoney, moneyTitle, remainingMoney, UNAVAILABLE } from "@/lib/money";

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
