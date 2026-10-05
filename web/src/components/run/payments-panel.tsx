import { formatMoney, moneyTitle } from "@/lib/money";
import {
  basescanTxUrl,
  denialText,
  type PaymentRow,
  type PaymentState,
} from "@/lib/run-view-model";

const STATE_TEXT: Record<PaymentState, string> = {
  "awaiting-approval": "Awaiting your approval",
  approved: "Approved, paying",
  rejected: "Rejected",
  expired: "Approval expired",
  settled: "Settled",
  denied: "Denied",
  ambiguous: "Outcome unknown",
};

export function PaymentsPanel({ payments }: { payments: readonly PaymentRow[] }) {
  if (payments.length === 0) {
    return <p className="text-muted-foreground text-sm">No payments in this run yet.</p>;
  }
  return (
    <table className="w-full text-left text-sm">
      <caption className="sr-only">Payments in this run</caption>
      <thead>
        <tr>
          <th scope="col" className="py-1 pr-3">
            Status
          </th>
          <th scope="col" className="py-1 pr-3">
            Amount
          </th>
          <th scope="col" className="py-1">
            Details
          </th>
        </tr>
      </thead>
      <tbody>
        {payments.map((row) => {
          const url = row.txHash ? basescanTxUrl(row.txHash) : null;
          return (
            <tr key={row.key} className="border-t">
              <td className="py-1 pr-3">{STATE_TEXT[row.state]}</td>
              <td className="py-1 pr-3" title={moneyTitle(row.amount)}>
                {formatMoney(row.amount)}
              </td>
              <td className="py-1">
                {url ? (
                  <a
                    className="underline underline-offset-4"
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    View on Basescan (Sepolia, new tab)
                  </a>
                ) : row.reason ? (
                  denialText(row.reason)
                ) : (
                  (row.payTo ?? "")
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
