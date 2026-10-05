import type { PaymentIntentStatus, RunPaymentItem } from "@/lib/api/types";
import { formatMoney, moneyTitle, UNAVAILABLE } from "@/lib/money";
import { basescanTxUrl } from "@/lib/run-view-model";

const STATUS: Record<PaymentIntentStatus, { glyph: string; text: string; hint?: string }> = {
  PENDING: { glyph: "○", text: "Pending" },
  AWAITING_APPROVAL: { glyph: "⏸", text: "Awaiting approval" },
  APPROVED: { glyph: "✓", text: "Approved" },
  RESERVED: { glyph: "◐", text: "Budget reserved" },
  SIGNED: { glyph: "✎", text: "Signed, settling" },
  SETTLED: { glyph: "✓", text: "Settled" },
  HELD: {
    glyph: "◔",
    text: "Held",
    hint: "Outcome not confirmed on chain yet. It resolves to settled or released by itself.",
  },
  RELEASED: { glyph: "↺", text: "Released", hint: "The reservation was returned to the budget." },
  DENIED: { glyph: "✕", text: "Denied" },
  REJECTED: { glyph: "✕", text: "Rejected" },
  EXPIRED: { glyph: "✕", text: "Approval expired" },
};

/** Payment intents of a run as the server sees them now (HELD resolves without a run event). */
export function PaymentIntentsPanel({ items }: { items: readonly RunPaymentItem[] }) {
  if (items.length === 0) {
    return <p className="text-muted-foreground text-sm">No payments in this run yet.</p>;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Payment intents of this run</caption>
        <thead>
          <tr>
            {["Status", "Amount", "Tool", "Payee", "Transaction"].map((heading) => (
              <th key={heading} scope="col" className="py-1 pr-3">
                {heading}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const status = STATUS[item.status];
            const url = item.txHash ? basescanTxUrl(item.txHash) : null;
            return (
              <tr key={item.paymentIntentId} className="border-t align-top">
                <td className="py-1 pr-3 whitespace-nowrap">
                  <span aria-hidden="true">{status.glyph} </span>
                  {status.text}
                  {status.hint ? <span className="sr-only">. {status.hint}</span> : null}
                  {status.hint ? (
                    <span className="text-muted-foreground block text-xs" aria-hidden="true">
                      {status.hint}
                    </span>
                  ) : null}
                </td>
                <td
                  className="py-1 pr-3 whitespace-nowrap"
                  title={item.amount ? moneyTitle(item.amount) : undefined}
                >
                  {item.amount ? formatMoney(item.amount) : UNAVAILABLE}
                </td>
                <td className="py-1 pr-3">{item.tool}</td>
                <td className="py-1 pr-3 font-mono break-all">{item.payTo ?? UNAVAILABLE}</td>
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
                  ) : (
                    UNAVAILABLE
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
