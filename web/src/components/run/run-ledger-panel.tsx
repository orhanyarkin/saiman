import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { runLedgerPaymentsQuery } from "@/lib/api/queries";
import { useNow } from "@/lib/hooks";
import {
  buyerStateText,
  chainStateText,
  impliedEntryCount,
  ledgerPollPhase,
  sellerStateText,
  shortId,
  shortState,
} from "@/lib/ledger-model";
import { formatMoney, moneyTitle } from "@/lib/money";

/**
 * "In the ledger": the run's payments as the ledger booked them. The ledger consumes Kafka
 * asynchronously, so after the first payment event and again after the terminal event this polls
 * every 2 s for up to 60 s (stopping early once every payment is booked in both books).
 * `armedAt` is the epoch ms of the event that armed the poll; it comes from event timestamps so
 * the component stays pure and a long-finished run is read once without polling.
 */
export function RunLedgerPanel({ runId, armedAt }: { runId: string; armedAt: number }) {
  const now = useNow(2000);
  const payments = useQuery(runLedgerPaymentsQuery(runId, armedAt));
  const items = payments.data?.items ?? [];
  const phase = ledgerPollPhase(armedAt, now, items);

  return (
    <section aria-labelledby="in-ledger-title" className="space-y-3">
      <h2 id="in-ledger-title" className="text-lg font-semibold">
        In the ledger
      </h2>
      {payments.isError ? (
        <ErrorNotice error={payments.error} />
      ) : payments.isPending ? (
        <p role="status" aria-busy="true">
          Looking up this run's payments in the ledger…
        </p>
      ) : items.length === 0 ? (
        phase === "gave-up" ? (
          <p className="text-muted-foreground text-sm">
            Nothing here yet. The ledger reads payment events from Kafka asynchronously, which can
            take a little longer. Open the{" "}
            <Link to="/ledger" className="underline underline-offset-4">
              Ledger page
            </Link>{" "}
            and check again in a moment.
          </p>
        ) : (
          <p role="status" className="text-muted-foreground text-sm">
            Waiting for the ledger to book this run's payments…
          </p>
        )
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">This run's payments in the ledger</caption>
              <thead>
                <tr>
                  {["Payment", "Amount", "Buyer", "Seller", "Chain", "Entries"].map((heading) => (
                    <th key={heading} scope="col" className="py-1 pr-3 font-semibold">
                      {heading}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {items.map((payment) => (
                  <tr key={payment.paymentId} className="border-t">
                    <th scope="row" className="py-1 pr-3 font-normal">
                      <Link
                        to="/ledger/payments/$paymentId"
                        params={{ paymentId: payment.paymentId }}
                        className="underline underline-offset-4"
                        title={payment.paymentId}
                      >
                        {shortId(payment.paymentId)}
                        <span className="sr-only"> ledger entries</span>
                      </Link>
                    </th>
                    <td className="py-1 pr-3" title={moneyTitle(payment.amount)}>
                      {formatMoney(payment.amount)}
                    </td>
                    <td className="py-1 pr-3" title={buyerStateText(payment.buyerState)}>
                      {shortState(buyerStateText(payment.buyerState))}
                    </td>
                    <td className="py-1 pr-3" title={sellerStateText(payment.sellerState)}>
                      {shortState(sellerStateText(payment.sellerState))}
                    </td>
                    <td className="py-1 pr-3" title={chainStateText(payment.chainState)}>
                      {shortState(chainStateText(payment.chainState))}
                    </td>
                    <td className="py-1 pr-3">{impliedEntryCount(payment)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {phase === "polling" ? (
            <p role="status" className="text-muted-foreground text-sm">
              Still booking: checking again every 2 seconds.
            </p>
          ) : null}
        </>
      )}
    </section>
  );
}
