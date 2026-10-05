import { Link } from "@tanstack/react-router";

import type { PaymentSummary } from "@/lib/api/types";
import {
  buyerStateText,
  chainStateText,
  sellerStateText,
  shortId,
  shortState,
} from "@/lib/ledger-model";
import { formatMoney, moneyTitle } from "@/lib/money";

/** Ledger payments: one row per authorization with the three states side by side. */
export function PaymentsTable({
  payments,
  caption,
}: {
  payments: readonly PaymentSummary[];
  caption: string;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {["Payment", "Amount", "Buyer", "Seller", "Chain", "Run", "Updated"].map((heading) => (
              <th key={heading} scope="col" className="py-2 pr-4 font-semibold">
                {heading}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {payments.map((payment) => (
            <tr key={payment.paymentId} className="border-t align-top">
              <th scope="row" className="py-2 pr-4 font-normal whitespace-nowrap">
                <Link
                  to="/ledger/payments/$paymentId"
                  params={{ paymentId: payment.paymentId }}
                  className="underline underline-offset-4"
                  title={payment.paymentId}
                >
                  {shortId(payment.paymentId)}
                  <span className="sr-only"> payment details</span>
                </Link>
              </th>
              <td className="py-2 pr-4 whitespace-nowrap" title={moneyTitle(payment.amount)}>
                {formatMoney(payment.amount)}
              </td>
              <td className="py-2 pr-4" title={buyerStateText(payment.buyerState)}>
                {shortState(buyerStateText(payment.buyerState))}
              </td>
              <td className="py-2 pr-4" title={sellerStateText(payment.sellerState)}>
                {shortState(sellerStateText(payment.sellerState))}
              </td>
              <td className="py-2 pr-4" title={chainStateText(payment.chainState)}>
                {shortState(chainStateText(payment.chainState))}
              </td>
              <td className="py-2 pr-4">
                {payment.runId ? (
                  <Link
                    to="/runs/$runId"
                    params={{ runId: payment.runId }}
                    className="underline underline-offset-4"
                  >
                    {shortId(payment.runId)}
                    <span className="sr-only"> run</span>
                  </Link>
                ) : (
                  "—"
                )}
              </td>
              <td className="py-2 pr-4 whitespace-nowrap">
                <time dateTime={payment.updatedAt}>
                  {new Date(payment.updatedAt).toLocaleString()}
                </time>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
