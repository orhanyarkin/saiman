import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { PaymentsPanel } from "@/components/run/payments-panel";
import { ReportPanel } from "@/components/run/report-panel";
import { Stepper } from "@/components/run/stepper";
import type { PaymentRow } from "@/lib/run-view-model";

describe("Stepper", () => {
  it("lists the four steps with a text status and marks the running one as current", () => {
    render(
      <Stepper
        steps={{ PLANNER: "done", RESEARCHER: "running", RISK: "pending", SYNTHESIS: "pending" }}
      />,
    );
    const items = within(screen.getByRole("list", { name: "Run steps" })).getAllByRole("listitem");
    expect(items).toHaveLength(4);
    expect(items[0]).toHaveTextContent("Planner");
    expect(items[0]).toHaveTextContent("done");
    expect(items[1]).toHaveAttribute("aria-current", "step");
    expect(items[1]).toHaveTextContent("in progress");
    expect(items[3]).toHaveTextContent("waiting");
  });
});

describe("ReportPanel", () => {
  it("renders the answer as text and links only validated KAP citations", () => {
    render(
      <ReportPanel
        report={{
          answer: "<script>alert(1)</script> Yanıt",
          citations: [
            {
              chunkId: "c1",
              sourceUrl: "https://www.kap.org.tr/tr/Bildirim/1",
              title: "KAP bildirimi",
            },
            { chunkId: "c2", sourceUrl: "https://evil.example/x", title: "Dış kaynak" },
            { chunkId: "c3", sourceUrl: "javascript:alert(1)", title: "Kötü" },
          ],
        }}
      />,
    );
    expect(screen.getByText(/<script>alert\(1\)<\/script>/)).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();

    const link = screen.getByRole("link", { name: "KAP bildirimi" });
    expect(link).toHaveAttribute("href", "https://www.kap.org.tr/tr/Bildirim/1");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.getByText("Dış kaynak")).toBeInTheDocument();
    expect(screen.getByText(/Yanıt/)).toHaveAttribute("lang", "tr");
  });

  it("says so when there are no citations", () => {
    render(<ReportPanel report={{ answer: "x", citations: [] }} />);
    expect(screen.getByText("No citations.")).toBeInTheDocument();
  });
});

describe("PaymentsPanel", () => {
  const usdc = (atomicUnits: number) => ({ atomicUnits, asset: "USDC", decimals: 6 });
  const hash = `0x${"b".repeat(64)}`;

  it("shows an empty state", () => {
    render(<PaymentsPanel payments={[]} />);
    expect(screen.getByText(/No payments/)).toBeInTheDocument();
  });

  it("links settled payments to Basescan and explains denials", () => {
    const rows: PaymentRow[] = [
      { key: "a", state: "settled", amount: usdc(20000), txHash: hash },
      { key: "b", state: "denied", amount: usdc(20000), reason: "RUN_BUDGET" },
      { key: "c", state: "settled", amount: usdc(10000), txHash: "not-a-hash" },
    ];
    render(<PaymentsPanel payments={rows} />);
    expect(screen.getByRole("table", { name: "Payments in this run" })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /Basescan/ });
    expect(link).toHaveAttribute("href", `https://sepolia.basescan.org/tx/${hash}`);
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.getByText("run budget would be exceeded")).toBeInTheDocument();
    expect(screen.getAllByText("0.02 USDC")).toHaveLength(2);
  });
});
