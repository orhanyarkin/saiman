import { Link } from "@tanstack/react-router";

import type { PaymentDetail, PaymentSummary } from "@/lib/api/types";
import {
  basescanAddressUrl,
  buyerStateText,
  chainStateText,
  mismatchText,
  sellerStateText,
} from "@/lib/ledger-model";
import { formatMoney, moneyTitle } from "@/lib/money";
import { basescanTxUrl } from "@/lib/run-view-model";

function TxLink({ label, hash }: { label: string; hash: string | null | undefined }) {
  const url = hash ? basescanTxUrl(hash) : null;
  if (!url) {
    return null;
  }
  return (
    <a
      className="underline underline-offset-4"
      href={url}
      target="_blank"
      rel="noopener noreferrer"
    >
      {label}: view on Basescan (Sepolia, new tab)
    </a>
  );
}

function SummaryBlock({ payment }: { payment: PaymentSummary }) {
  const payee = basescanAddressUrl(payment.payTo);
  return (
    <section aria-labelledby="pd-summary" className="space-y-3">
      <h2 id="pd-summary" className="text-lg font-semibold">
        Summary
      </h2>
      <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-[10rem_1fr]">
        <dt className="font-medium">Amount</dt>
        <dd title={moneyTitle(payment.amount)}>{formatMoney(payment.amount)}</dd>
        <dt className="font-medium">Payee</dt>
        <dd className="break-all">
          {payee ? (
            <a
              className="underline underline-offset-4"
              href={payee}
              target="_blank"
              rel="noopener noreferrer"
            >
              {payment.payTo}
              <span className="sr-only"> on Basescan (Sepolia, new tab)</span>
            </a>
          ) : (
            payment.payTo
          )}
        </dd>
        <dt className="font-medium">Buyer's book</dt>
        <dd>{buyerStateText(payment.buyerState)}</dd>
        <dt className="font-medium">Seller's book</dt>
        <dd>{sellerStateText(payment.sellerState)}</dd>
        <dt className="font-medium">On chain</dt>
        <dd>{chainStateText(payment.chainState)}</dd>
        <dt className="font-medium">Run</dt>
        <dd>
          {payment.runId ? (
            <Link
              to="/runs/$runId"
              params={{ runId: payment.runId }}
              className="underline underline-offset-4"
            >
              Open the research run
            </Link>
          ) : (
            "Not linked to a run"
          )}
        </dd>
        <dt className="font-medium">Transactions</dt>
        <dd className="space-y-1">
          <TxLink label="Chain" hash={payment.chainTxHash} />
          <br />
          <TxLink label="Buyer" hash={payment.buyerTxHash} />
          <br />
          <TxLink label="Seller" hash={payment.sellerTxHash} />
          {payment.chainTxHash || payment.buyerTxHash || payment.sellerTxHash
            ? null
            : "No transaction recorded yet."}
        </dd>
      </dl>
    </section>
  );
}

/** One payment: states, journal entries with postings, and reconciliation findings. */
export function PaymentDetailView({ detail }: { detail: PaymentDetail }) {
  return (
    <div className="space-y-8">
      <SummaryBlock payment={detail.payment} />

      <section aria-labelledby="pd-entries" className="space-y-4">
        <h2 id="pd-entries" className="text-lg font-semibold">
          Journal entries
        </h2>
        {detail.entries.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            No entries yet. The ledger books a payment when it receives the matching events.
          </p>
        ) : (
          detail.entries.map((entry) => (
            <div key={entry.entryId} className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <caption className="pb-1 text-left font-medium">
                  {entry.kind} in the {entry.book.toLowerCase()} book: {entry.description}
                  <span className="text-muted-foreground block text-xs font-normal">
                    <time dateTime={entry.effectiveAt}>
                      {new Date(entry.effectiveAt).toLocaleString()}
                    </time>
                    {entry.reversesEntryId ? ` · reverses entry ${entry.reversesEntryId}` : ""}
                  </span>
                </caption>
                <thead>
                  <tr>
                    {["Account", "Side", "Amount"].map((heading) => (
                      <th key={heading} scope="col" className="py-1 pr-4 font-semibold">
                        {heading}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {entry.postings.map((posting, index) => (
                    <tr key={index} className="border-t">
                      <th scope="row" className="py-1 pr-4 font-normal break-all">
                        {posting.accountCode}
                      </th>
                      <td className="py-1 pr-4">{posting.side}</td>
                      <td className="py-1 pr-4" title={moneyTitle(posting.amount)}>
                        {formatMoney(posting.amount)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))
        )}
      </section>

      <section aria-labelledby="pd-findings" className="space-y-3">
        <h2 id="pd-findings" className="text-lg font-semibold">
          Reconciliation findings
        </h2>
        {detail.mismatches.length === 0 ? (
          <p className="text-muted-foreground text-sm">No findings for this payment.</p>
        ) : (
          <ul className="space-y-3">
            {detail.mismatches.map((finding, index) => (
              <li key={index} className="rounded-md border p-3 text-sm">
                <p className="font-medium">
                  <span aria-hidden="true">{"⚠ "}</span>
                  {finding.kind} ({finding.status})
                </p>
                <p>{mismatchText(finding.kind)}</p>
                <p className="text-muted-foreground">
                  Books: {finding.ledgerValue ? formatMoney(finding.ledgerValue) : "—"}
                  {" · "}Chain: {finding.chainValue ? formatMoney(finding.chainValue) : "—"}
                  {finding.adjustmentEntryId
                    ? ` · adjustment entry ${finding.adjustmentEntryId}`
                    : ""}
                </p>
                {finding.reconciliationRunId ? (
                  <Link
                    to="/reconciliation/$reconRunId"
                    params={{ reconRunId: finding.reconciliationRunId }}
                    className="underline underline-offset-4"
                  >
                    Reconciliation run
                  </Link>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
