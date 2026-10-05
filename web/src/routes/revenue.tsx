import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";

import { ErrorNotice } from "@/components/error-notice";
import { SellerRevenueCard } from "@/components/ledger/revenue-view";
import { revenueQuery } from "@/lib/api/queries";
import { useDocumentTitle } from "@/lib/hooks";
import { isAddress } from "@/lib/ledger-model";

export const Route = createFileRoute("/revenue")({
  component: RevenuePage,
});

function RevenuePage() {
  useDocumentTitle("Revenue");
  const [text, setText] = useState("");
  const filter = text.trim();
  const valid = filter === "" || isAddress(filter);
  const revenue = useQuery({
    ...revenueQuery(filter === "" ? null : filter),
    enabled: valid,
  });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Seller revenue</h1>
      <p className="text-muted-foreground text-sm">
        What the paid endpoints earned. The first figures are <strong>per books</strong>: taken from
        the ledger, not checked against the chain. The chain-verified figures count only payments
        Base Sepolia has confirmed.
      </p>

      <div className="space-y-1">
        <label htmlFor="pay-to" className="block text-sm font-medium">
          Filter by payee address (optional)
        </label>
        <input
          id="pay-to"
          type="text"
          value={text}
          placeholder="0x…"
          spellCheck={false}
          autoComplete="off"
          aria-invalid={!valid}
          aria-describedby="pay-to-error"
          className="border-input bg-background w-full max-w-md rounded-md border px-3 py-2 font-mono text-sm"
          onChange={(event) => {
            setText(event.target.value);
          }}
        />
        <p id="pay-to-error" role="alert" className="text-destructive text-sm font-medium">
          {valid ? "" : "Enter a full address: 0x followed by 40 hexadecimal characters."}
        </p>
      </div>

      {!valid ? null : revenue.isPending ? (
        <p role="status" aria-busy="true">
          Loading revenue…
        </p>
      ) : revenue.isError ? (
        <ErrorNotice error={revenue.error} />
      ) : revenue.data.items.length === 0 ? (
        <p className="text-muted-foreground">
          No sales yet. Revenue appears when a research run pays a seller endpoint.
        </p>
      ) : (
        <>
          {revenue.data.truncated ? (
            <p role="status" className="text-sm font-medium">
              <span aria-hidden="true">{"ℹ "}</span>
              Only the first sellers are shown; filter by payee address to see a specific one.
            </p>
          ) : null}
          {revenue.data.items.map((seller) => (
            <SellerRevenueCard key={seller.payTo} seller={seller} />
          ))}
        </>
      )}
    </div>
  );
}
