import type { SellerRevenue } from "@/lib/api/types";
import { formatMoney, moneyTitle } from "@/lib/money";

export const PER_BOOKS_HELP =
  "Per books: summed from the seller's ledger entries. It is NOT checked against the chain, so a forged event could change it. Reconciliation reports such differences but does not correct revenue.";
export const UNVERIFIED_HELP =
  "Gross sales the chain has not confirmed yet. Payments also count here for a while after they happen, because the public Base Sepolia node reports a block as safe a little late.";

function Amount({ money }: { money: SellerRevenue["grossSales"] }) {
  return <span title={moneyTitle(money)}>{formatMoney(money)}</span>;
}

/** One seller: per-books figures next to the chain-verified ones, plus what is still unverified. */
export function SellerRevenueCard({ seller }: { seller: SellerRevenue }) {
  const rows = [
    ["Gross sales", seller.grossSales, seller.chainVerified.grossSales],
    ["Credit notes", seller.creditNotes, seller.chainVerified.creditNotes],
    ["Net revenue", seller.netRevenue, seller.chainVerified.netRevenue],
  ] as const;
  return (
    <section aria-label={`Revenue of ${seller.payTo}`} className="space-y-3 rounded-md border p-4">
      <h2 className="text-base font-semibold break-all">
        {seller.payTo}
        {seller.saturated ? (
          <span
            className="ml-2 rounded border border-amber-600 bg-amber-100 px-2 py-0.5 text-sm font-medium whitespace-nowrap text-amber-950"
            title="A total reached the limit of what can be summed exactly; the figures are hidden or incomplete."
          >
            <span aria-hidden="true">{"⚠ "}</span>Saturated
          </span>
        ) : null}
      </h2>
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Revenue of {seller.payTo}</caption>
        <thead>
          <tr>
            <th scope="col" className="py-1 pr-4 font-semibold">
              Figure
            </th>
            <th scope="col" className="py-1 pr-4 font-semibold" title={PER_BOOKS_HELP}>
              Per books (not chain-verified)
            </th>
            <th scope="col" className="py-1 pr-4 font-semibold">
              Chain-verified
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([label, books, chain]) => (
            <tr key={label} className="border-t">
              <th scope="row" className="py-1 pr-4 font-normal">
                {label}
              </th>
              <td className="py-1 pr-4">
                <Amount money={books} />
              </td>
              <td className="py-1 pr-4">
                <Amount money={chain} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-muted-foreground text-xs">{PER_BOOKS_HELP}</p>
      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[14rem_1fr]">
        <dt className="font-medium">Unverified gross sales</dt>
        <dd>
          <Amount money={seller.unverifiedGrossSales} />
          <span className="text-muted-foreground block text-xs">{UNVERIFIED_HELP}</span>
        </dd>
        <dt className="font-medium">Open findings</dt>
        <dd>
          {seller.openFindings}
          {seller.openFindings > 0 ? " (see Reconciliation)" : ""}
        </dd>
        <dt className="font-medium">Sales / credited</dt>
        <dd>
          {seller.sales} sales, {seller.credited} credited
        </dd>
        <dt className="font-medium">Owed back to customers</dt>
        <dd>
          <Amount money={seller.customerCredits} />
        </dd>
      </dl>
    </section>
  );
}
