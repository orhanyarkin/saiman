import { checkTrialBalance, type BalanceCheck } from "@/lib/ledger-model";
import type { TrialBalanceRow } from "@/lib/api/types";
import { formatBigMoney, formatMoney, moneyTitle } from "@/lib/money";

const BOOK_TITLE: Record<string, string> = {
  BUYER: "Buyer book (what agents spend)",
  SELLER: "Seller book (what the data seller earns)",
};

function CheckLine({ check }: { check: BalanceCheck }) {
  if (check.status === "unverifiable" || check.debits === null || check.credits === null) {
    return (
      <p className="text-sm font-medium">
        <span aria-hidden="true">{"⚠ "}</span>
        {check.asset}: cannot verify. An amount is too large to read exactly, so no result is shown
        instead of a wrong one.
      </p>
    );
  }
  const total = formatBigMoney(check.debits, check.asset, check.decimals);
  if (check.status === "balanced") {
    return (
      <p className="text-sm font-medium">
        <span aria-hidden="true">{"✓ "}</span>
        {check.asset}: books balance. Σ debits = Σ credits = {total}.
      </p>
    );
  }
  return (
    <p role="alert" className="text-destructive text-sm font-medium">
      <span aria-hidden="true">{"⚠ "}</span>
      {check.asset}: books do NOT balance. Σ debits = {total}, Σ credits ={" "}
      {formatBigMoney(check.credits, check.asset, check.decimals)}.
    </p>
  );
}

/** Trial balance grouped by book, with a Σ debits = Σ credits check per asset. */
export function TrialBalanceView({ rows }: { rows: readonly TrialBalanceRow[] }) {
  const books = [...new Set(rows.map((row) => row.book))].sort();
  const checks = checkTrialBalance(rows);
  return (
    <div className="space-y-8">
      {books.map((book) => {
        const bookRows = rows.filter((row) => row.book === book);
        return (
          <section key={book} aria-labelledby={`tb-${book}`} className="space-y-3">
            <h2 id={`tb-${book}`} className="text-lg font-semibold">
              {BOOK_TITLE[book] ?? `${book} book`}
            </h2>
            {checks
              .filter((check) => check.book === book)
              .map((check) => (
                <CheckLine key={check.asset} check={check} />
              ))}
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">Trial balance, {book.toLowerCase()} book</caption>
                <thead>
                  <tr>
                    {["Account", "Type", "Debit", "Credit", "Balance"].map((heading) => (
                      <th key={heading} scope="col" className="py-2 pr-4 font-semibold">
                        {heading}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {bookRows.map((row) => {
                    const amount = (atomicUnits: number) => ({
                      atomicUnits,
                      asset: row.asset,
                      decimals: row.decimals,
                    });
                    return (
                      <tr key={row.account} className="border-t align-top">
                        <th scope="row" className="py-2 pr-4 font-normal break-all">
                          {row.account}
                        </th>
                        <td className="py-2 pr-4">{row.type}</td>
                        {[row.debit, row.credit, row.balance].map((value, index) => (
                          <td
                            key={index}
                            className="py-2 pr-4 whitespace-nowrap"
                            title={moneyTitle(amount(value))}
                          >
                            {formatMoney(amount(value))}
                          </td>
                        ))}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </section>
        );
      })}
    </div>
  );
}
