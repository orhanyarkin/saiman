import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { RunNowControl } from "@/components/ledger/reconciliation";
import { SellerRevenueCard } from "@/components/ledger/revenue-view";
import { TrialBalanceView } from "@/components/ledger/trial-balance";
import { RunLedgerPanel } from "@/components/run/run-ledger-panel";
import type { PaymentSummary, SellerRevenue, TrialBalanceRow } from "@/lib/api/types";
import { renderWithProviders } from "@/test/render";

const usdc = (atomicUnits: number) => ({ atomicUnits, asset: "USDC", decimals: 6 });

const row = (book: string, debit: number, credit: number, account: string): TrialBalanceRow => ({
  book,
  account,
  type: "ASSET",
  asset: "USDC",
  decimals: 6,
  debit,
  credit,
  balance: debit - credit,
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("TrialBalanceView", () => {
  it("groups by book and states that the books balance", async () => {
    await renderWithProviders(
      <TrialBalanceView
        rows={[
          row("BUYER", 20000, 0, "buyer:x:expense"),
          row("BUYER", 0, 20000, "buyer:x:wallet"),
          row("SELLER", 20000, 0, "seller:y:wallet"),
          row("SELLER", 0, 20000, "seller:y:revenue"),
        ]}
      />,
    );
    expect(screen.getByRole("heading", { name: /Buyer book/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Seller book/ })).toBeInTheDocument();
    expect(screen.getAllByText(/books balance\. Σ debits = Σ credits = 0\.02 USDC/)).toHaveLength(
      2,
    );
    const table = screen.getByRole("table", { name: "Trial balance, buyer book" });
    expect(within(table).getByRole("rowheader", { name: "buyer:x:wallet" })).toBeInTheDocument();
  });

  it("flags an unbalanced book as an alert", async () => {
    await renderWithProviders(
      <TrialBalanceView rows={[row("BUYER", 20000, 0, "a"), row("BUYER", 0, 10000, "b")]} />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/do NOT balance/);
  });

  it("refuses to check amounts beyond 2^53 and hides them instead of showing them wrong", async () => {
    await renderWithProviders(
      <TrialBalanceView
        rows={[row("BUYER", 2 ** 53 + 2, 0, "a"), row("BUYER", 0, 2 ** 53 + 2, "b")]}
      />,
    );
    expect(screen.getByText(/cannot verify/)).toBeInTheDocument();
    expect(screen.queryByText(/books balance/)).not.toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });
});

describe("SellerRevenueCard", () => {
  const seller: SellerRevenue = {
    payTo: "0x1111111111111111111111111111111111111111",
    grossSales: usdc(40000),
    creditNotes: usdc(0),
    netRevenue: usdc(40000),
    customerCredits: usdc(0),
    sales: 2,
    credited: 0,
    chainVerified: { grossSales: usdc(20000), creditNotes: usdc(0), netRevenue: usdc(20000) },
    unverifiedGrossSales: usdc(20000),
    openFindings: 1,
    saturated: true,
  };

  it("labels the top-level figures per books and shows the chain-verified ones beside them", async () => {
    await renderWithProviders(<SellerRevenueCard seller={seller} />);
    expect(
      screen.getByRole("columnheader", { name: "Per books (not chain-verified)" }),
    ).toBeVisible();
    expect(screen.getByRole("columnheader", { name: "Chain-verified" })).toBeVisible();
    const gross = screen.getByRole("row", { name: /Gross sales/ });
    expect(gross).toHaveTextContent("0.04 USDC");
    expect(gross).toHaveTextContent("0.02 USDC");
    expect(screen.getByText("Unverified gross sales")).toBeVisible();
    expect(screen.getByText(/safe a little late/)).toBeVisible();
    expect(screen.getByText("Open findings").nextSibling).toHaveTextContent("1");
    expect(screen.getByText(/Saturated/)).toBeVisible();
  });
});

function respond(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": status >= 400 ? "application/problem+json" : "application/json",
      ...headers,
    },
  });
}

describe("RunNowControl", () => {
  it("starts a run, then explains 409 (already running)", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(respond(202, { runId: "9b1f0c52-3a7e-4d68-8f21-5c0d1e2f3a61" }))
      .mockResolvedValueOnce(
        respond(409, { detail: "a reconciliation run is already in progress" }),
      );
    await renderWithProviders(<RunNowControl />);
    fireEvent.click(screen.getByRole("button", { name: "Run now" }));
    expect(await screen.findByText(/Reconciliation started/)).toBeVisible();
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      method: "POST",
      headers: expect.objectContaining({ "X-Saiman-Csrf": "1" }) as unknown,
    });
    fireEvent.click(screen.getByRole("button", { name: "Run now" }));
    expect(await screen.findByText(/already running/)).toBeVisible();
  });

  it("disables the button for the Retry-After period on 429, with one static announcement", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
      respond(429, { detail: "too soon" }, { "Retry-After": "30" }),
    );
    await renderWithProviders(<RunNowControl />);
    fireEvent.click(screen.getByRole("button", { name: "Run now" }));
    const note = await screen.findByText(/run it again in about 30 seconds/);
    expect(note).toBeVisible();
    expect(screen.getByRole("button", { name: "Run now" })).toBeDisabled();
    // The ticking number is hidden from assistive technology.
    expect(screen.getByText(/s left\)/)).toHaveAttribute("aria-hidden", "true");
  });

  it("explains 503 as the ledger starting", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(respond(503, { detail: "starting" }));
    await renderWithProviders(<RunNowControl />);
    fireEvent.click(screen.getByRole("button", { name: "Run now" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/ledger is starting/);
    expect(screen.getByRole("button", { name: "Run now" })).toBeEnabled();
  });
});

describe("RunLedgerPanel", () => {
  const payment: PaymentSummary = {
    paymentId: "3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e51",
    runId: "6ad4354c-8e79-4b49-b5d9-d45eb9689b41",
    payTo: "0x1111111111111111111111111111111111111111",
    amount: usdc(20000),
    buyerState: "SETTLED",
    sellerState: "SETTLED",
    chainState: "UNKNOWN",
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
  };

  it("shows the run's payments with states, entries count and the drill-down link", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(() =>
        Promise.resolve(respond(200, { items: [payment], nextCursor: null })),
      );
    await renderWithProviders(<RunLedgerPanel runId={payment.runId ?? ""} armedAt={Date.now()} />);
    const table = await screen.findByRole("table", { name: /payments in the ledger/ });
    expect(within(table).getByRole("link", { name: /ledger entries/ })).toHaveAttribute(
      "href",
      `/ledger/payments/${payment.paymentId}`,
    );
    expect(table).toHaveTextContent("0.02 USDC");
    expect(table).toHaveTextContent("3"); // ENCUMBER + SETTLE + SALE
    expect(fetchMock.mock.calls[0]?.[0] as string).toBe(
      `/api/v1/ledger/payments?runId=${payment.runId ?? ""}&limit=50`,
    );
    // Fully booked: the poll is over.
    expect(screen.queryByText(/Still booking/)).not.toBeInTheDocument();
  });

  it("keeps waiting inside the window and explains calmly after it", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(() =>
      Promise.resolve(respond(200, { items: [], nextCursor: null })),
    );
    const { unmount } = await renderWithProviders(
      <RunLedgerPanel runId="r1" armedAt={Date.now()} />,
    );
    expect(await screen.findByText(/Waiting for the ledger/)).toBeVisible();
    unmount();

    await renderWithProviders(<RunLedgerPanel runId="r2" armedAt={Date.now() - 61_000} />);
    await waitFor(() => {
      expect(screen.getByText(/reads payment events from Kafka asynchronously/)).toBeVisible();
    });
    expect(screen.getByRole("link", { name: "Ledger page" })).toHaveAttribute("href", "/ledger");
  });
});
