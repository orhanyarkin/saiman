import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";

import { ErrorNotice } from "@/components/error-notice";
import { PaymentsTable } from "@/components/ledger/payments-table";
import { TrialBalanceView } from "@/components/ledger/trial-balance";
import { Button, buttonVariants } from "@/components/ui/button";
import { ledgerPaymentsQuery, trialBalanceQuery } from "@/lib/api/queries";
import { useCapabilities } from "@/lib/use-capabilities";
import type { LedgerBook } from "@/lib/api/types";
import { useDocumentTitle } from "@/lib/hooks";

export const Route = createFileRoute("/ledger/")({
  component: LedgerPage,
});

const BOOK_OPTIONS: readonly { value: "" | LedgerBook; label: string }[] = [
  { value: "", label: "Both books" },
  { value: "BUYER", label: "Buyer book" },
  { value: "SELLER", label: "Seller book" },
];

function LedgerPage() {
  useDocumentTitle("Ledger");
  const [book, setBook] = useState<"" | LedgerBook>("");
  const { canOperate } = useCapabilities();
  const balance = useQuery(trialBalanceQuery());
  const payments = useInfiniteQuery(ledgerPaymentsQuery({ book: book === "" ? null : book }));
  const pageError: unknown = payments.isFetchNextPageError ? payments.error : null;
  const items = payments.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold">Ledger</h1>
      <p className="text-muted-foreground text-sm">
        Every payment is booked twice, in the buyer's and in the seller's book, and each book always
        balances: total debits equal total credits.
      </p>

      <section aria-labelledby="balances-title" className="space-y-4">
        <h2 id="balances-title" className="text-xl font-semibold">
          Balances by account
        </h2>
        {balance.isPending ? (
          <p role="status" aria-busy="true">
            Loading balances…
          </p>
        ) : balance.isError ? (
          <ErrorNotice error={balance.error} />
        ) : balance.data.length === 0 ? (
          <div className="space-y-3">
            <p className="text-muted-foreground">No accounts yet: nothing has been booked.</p>
            {canOperate ? (
              <Link to="/runs/new" className={buttonVariants()}>
                Start a research run
              </Link>
            ) : null}
          </div>
        ) : (
          <TrialBalanceView rows={balance.data} />
        )}
      </section>

      <section aria-labelledby="payments-title" className="space-y-4">
        <h2 id="payments-title" className="text-xl font-semibold">
          Payments
        </h2>
        <div className="space-y-1">
          <label htmlFor="book-filter" className="block text-sm font-medium">
            Show payments booked in
          </label>
          <select
            id="book-filter"
            value={book}
            className="border-input bg-background rounded-md border px-3 py-2 text-sm"
            onChange={(event) => {
              setBook(event.target.value as "" | LedgerBook);
            }}
          >
            {BOOK_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        {payments.isPending ? (
          <p role="status" aria-busy="true">
            Loading payments…
          </p>
        ) : payments.isError ? (
          <div className="space-y-3">
            <ErrorNotice error={payments.error} />
            <Button
              variant="outline"
              onClick={() => {
                void payments.refetch();
              }}
            >
              Try again
            </Button>
          </div>
        ) : items.length === 0 ? (
          <div className="space-y-3">
            <p className="text-muted-foreground">No payments in the ledger yet.</p>
            {canOperate ? (
              <Link to="/runs/new" className={buttonVariants()}>
                Start a research run
              </Link>
            ) : null}
          </div>
        ) : (
          <>
            <PaymentsTable payments={items} caption="Ledger payments, newest first" />
            <p role="status" className="text-muted-foreground text-sm">
              Showing {items.length} payment{items.length === 1 ? "" : "s"}.
            </p>
            {pageError === null ? null : <ErrorNotice error={pageError} />}
            {payments.hasNextPage ? (
              <Button
                variant="outline"
                disabled={payments.isFetchingNextPage}
                onClick={() => {
                  void payments.fetchNextPage();
                }}
              >
                {payments.isFetchingNextPage ? "Loading…" : "Load more"}
              </Button>
            ) : null}
          </>
        )}
      </section>
    </div>
  );
}
