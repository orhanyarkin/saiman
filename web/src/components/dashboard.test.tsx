import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApprovalRow } from "@/components/approvals/approval-row";
import { PaymentIntentsPanel } from "@/components/run/payment-intents-panel";
import { RunsTable } from "@/components/run/runs-table";
import { SpendOverviewView } from "@/components/spend/spend-overview";
import type { ApprovalView, RunListItem, RunPaymentItem, SpendOverview } from "@/lib/api/types";
import { formatDocumentTitle } from "@/lib/hooks";
import { renderWithProviders } from "@/test/render";

const money = (atomicUnits: number, asset = "USDC") => ({ atomicUnits, asset, decimals: 6 });

const run: RunListItem = {
  runId: "6ad4354c-8e79-4b49-b5d9-d45eb9689b41",
  status: "AWAITING_APPROVAL",
  question: "THYAO son özel durum açıklamaları neler?",
  budget: money(50000),
  committed: money(20000),
  reserved: money(10000),
  cost: { paymentsUsdc: money(20000), llmUsd: money(6923, "USD"), totalUsd: money(26923, "USD") },
  createdAt: "2026-10-01T10:00:01Z",
  pendingApprovals: 2,
};

describe("RunsTable", () => {
  it("shows question, status with glyph, cost, budget meter and the approvals badge", async () => {
    await renderWithProviders(<RunsTable runs={[run]} caption="Runs" />);
    const table = screen.getByRole("table", { name: "Runs" });
    expect(within(table).getAllByRole("columnheader")).toHaveLength(5);
    const link = within(table).getByRole("link", { name: run.question });
    expect(link).toHaveAttribute("lang", "tr");
    expect(link).toHaveAttribute("href", `/runs/${run.runId}`);
    expect(table).toHaveTextContent("Waiting for your approval");
    expect(table).toHaveTextContent("0.02 USDC + $0.0069");
    expect(table).toHaveTextContent("0.02 USDC of 0.05 USDC");
    expect(table).toHaveTextContent("2 approvals pending");
    expect(within(table).getByRole("meter", { name: "Budget used" })).toHaveProperty(
      "value",
      20000,
    );
  });

  it("omits the badge when nothing is pending", async () => {
    await renderWithProviders(
      <RunsTable runs={[{ ...run, pendingApprovals: 0, status: "SUCCEEDED" }]} caption="Runs" />,
    );
    expect(screen.queryByText(/pending/)).toBeNull();
    expect(screen.getByRole("table")).toHaveTextContent("Completed");
  });
});

describe("PaymentIntentsPanel", () => {
  const base = {
    paymentIntentId: "p1",
    tool: "disclosure-questions",
    resource: "http://seller/x",
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
  };

  it("explains HELD, links settled transactions and tolerates a missing amount", () => {
    const hash = `0x${"ab".repeat(32)}`;
    const items: RunPaymentItem[] = [
      { ...base, status: "SETTLED", amount: money(20000), payTo: "0xpay", txHash: hash },
      { ...base, paymentIntentId: "p2", status: "HELD", amount: money(10000), payTo: "0xpay" },
      { ...base, paymentIntentId: "p3", status: "DENIED" },
    ];
    render(<PaymentIntentsPanel items={items} />);
    const table = screen.getByRole("table", { name: "Payment intents of this run" });
    expect(table).toHaveTextContent("Settled");
    expect(table).toHaveTextContent("Held");
    expect(table).toHaveTextContent("resolves to settled or released by itself");
    expect(table).toHaveTextContent("—");
    const link = within(table).getByRole("link", { name: /Basescan/ });
    expect(link).toHaveAttribute("href", `https://sepolia.basescan.org/tx/${hash}`);
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("shows an empty state", () => {
    render(<PaymentIntentsPanel items={[]} />);
    expect(screen.getByText("No payments in this run yet.")).toBeVisible();
  });
});

describe("ApprovalRow", () => {
  const approval: ApprovalView = {
    id: "0b5c6a8e-3c1d-4b1e-9f0a-2f6d7c1e9a01",
    runId: run.runId,
    paymentIntentId: "75f93933-72b5-4604-ad2c-063e3f4e6ed6",
    amountAtomic: 20000,
    payTo: "0x1111111111111111111111111111111111111111",
    resource: "http://seller-api:8081/v1/disclosures/THYAO/questions",
    status: "PENDING",
    requestedAt: "2026-10-01T10:00:00Z",
    expiresAt: "2999-01-01T00:00:00Z",
  };

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("formats the plain-number amount as USDC and posts the decision", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ approvalId: approval.id, status: "APPROVED" }), {
        status: 200,
      }),
    );
    await renderWithProviders(
      <ul>
        <ApprovalRow approval={approval} />
      </ul>,
    );
    expect(screen.getByText("0.02 USDC")).toBeVisible();
    expect(screen.getByRole("link", { name: "Open the run" })).toHaveAttribute(
      "href",
      `/runs/${run.runId}`,
    );
    fireEvent.click(screen.getByRole("button", { name: /^Approve 0.02 USDC to 0x1111/ }));
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent("Approved.");
    });
    const [url, init] = vi.mocked(fetch).mock.calls[0] ?? [];
    expect(url as string).toBe(`/api/v1/runs/${run.runId}/approvals/${approval.id}`);
    expect(JSON.parse(init?.body as string)).toEqual({ decision: "APPROVE" });
    expect(screen.getByRole("button", { name: /^Reject/ })).toBeDisabled();
  });

  it("treats 409 as already decided: explains it and locks the buttons", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ status: 409, detail: "approval was already decided" }), {
        status: 409,
        headers: { "content-type": "application/problem+json" },
      }),
    );
    await renderWithProviders(
      <ul>
        <ApprovalRow approval={approval} />
      </ul>,
    );
    fireEvent.click(screen.getByRole("button", { name: /^Reject/ }));
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/already decided or has expired/);
    });
    expect(screen.getByRole("button", { name: /^Approve/ })).toBeDisabled();
  });
});

describe("SpendOverviewView", () => {
  const spend: SpendOverview = {
    day: "2026-10-05",
    dailyCap: money(1_000_000),
    dayReserved: money(10_000),
    dayCommitted: money(50_000),
    limits: {
      defaultRunBudget: money(50_000),
      maxRunBudget: money(200_000),
      approvalThreshold: money(10_000),
    },
    byTool: [
      { tool: "disclosure-questions", status: "SETTLED", count: 2, amount: money(40_000) },
      { tool: "disclosure-search", status: "HELD", count: 1, amount: money(10_000) },
    ],
  };

  it("shows cap, remaining, limits and the per-tool table with scoped headers", () => {
    render(<SpendOverviewView spend={spend} />);
    expect(screen.getByRole("heading", { name: "Remaining" })).toBeVisible();
    expect(screen.getByText("0.94 USDC")).toBeVisible();
    expect(screen.getByRole("meter", { name: "Committed of daily cap" })).toHaveProperty(
      "value",
      50000,
    );
    const table = screen.getByRole("table", { name: /2026-10-05 by tool and status/ });
    for (const header of within(table).getAllByRole("columnheader")) {
      expect(header).toHaveAttribute("scope", "col");
    }
    expect(table).toHaveTextContent("HELD");
    expect(screen.getByText("No separate limit")).toBeVisible();
    expect(screen.getByText(/outside the language model/)).toBeVisible();
  });

  it("shows an empty state without tool spend", () => {
    render(<SpendOverviewView spend={{ ...spend, byTool: [] }} />);
    expect(screen.getByText("No payments on this day.")).toBeVisible();
    expect(screen.queryByRole("table")).toBeNull();
  });
});

describe("formatDocumentTitle", () => {
  it("prefixes the pending approval count only when something is pending", () => {
    expect(formatDocumentTitle("Runs", 2)).toBe("(2) Approval needed · Runs · Saiman");
    expect(formatDocumentTitle("Runs", 0)).toBe("Runs · Saiman");
    expect(formatDocumentTitle("Runs", null)).toBe("Runs · Saiman");
  });
});
