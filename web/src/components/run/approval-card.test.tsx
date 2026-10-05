import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApprovalCard } from "@/components/run/approval-card";
import type { ApprovalRequest } from "@/lib/run-view-model";
import { READER_ME, testQueryClient } from "@/test/render";

const approval: ApprovalRequest = {
  approvalId: "0b5c6a8e-3c1d-4b1e-9f0a-2f6d7c1e9a01",
  paymentIntentId: "75f93933-72b5-4604-ad2c-063e3f4e6ed6",
  amount: { atomicUnits: 20000, asset: "USDC", decimals: 6 },
  payTo: "0x1111111111111111111111111111111111111111",
  resource: "http://seller-api:8081/v1/disclosures/THYAO/questions",
  expiresAt: "2026-10-01T10:05:00Z",
};

function renderCard(onSettled = vi.fn(), me = undefined as typeof READER_ME | undefined) {
  const client = testQueryClient(me);
  render(
    <QueryClientProvider client={client}>
      <ApprovalCard runId="run-1" approval={approval} onSettled={onSettled} />
    </QueryClientProvider>,
  );
  return onSettled;
}

function problem(status: number, detail: string) {
  return new Response(JSON.stringify({ status, detail }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });
}

describe("ApprovalCard", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"] });
    vi.setSystemTime(new Date("2026-10-01T10:00:00Z"));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("shows amount, payee, resource and a hidden countdown inside an assertive alert", () => {
    renderCard();
    expect(screen.getByRole("alert")).toHaveTextContent(
      `Approval needed: pay 0.02 USDC to ${approval.payTo}`,
    );
    const card = screen.getByRole("region", { name: /Approval needed/ });
    expect(card).toHaveTextContent(approval.resource);
    expect(within(card).queryByRole("alert")).toBeInTheDocument();
    // The ticking countdown must not be exposed to assistive tech.
    expect(screen.getByText("(5:00 left)")).toHaveAttribute("aria-hidden", "true");
  });

  it("sends APPROVE with the CSRF and JSON headers", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ approvalId: approval.approvalId, status: "APPROVED" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const onSettled = renderCard();
    fireEvent.click(screen.getByRole("button", { name: /Approve 0.02 USDC/ }));

    await waitFor(() => {
      expect(onSettled).toHaveBeenCalled();
    });
    const [url, init] = vi.mocked(fetch).mock.calls[0] ?? [];
    expect(url).toBe(`/api/v1/runs/run-1/approvals/${approval.approvalId}`);
    expect(init?.method).toBe("POST");
    expect(init?.headers).toMatchObject({
      "Content-Type": "application/json",
      "X-Saiman-Csrf": "1",
    });
    expect(init?.body).toBe(JSON.stringify({ decision: "APPROVE" }));
    expect(screen.getByRole("status")).toHaveTextContent(/Approved/);
    expect(screen.getByRole("button", { name: /Approve/ })).toBeDisabled();
  });

  it("sends REJECT", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ approvalId: approval.approvalId, status: "REJECTED" }), {
        status: 200,
      }),
    );
    renderCard();
    fireEvent.click(screen.getByRole("button", { name: "Reject" }));
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/Rejected/);
    });
    expect(vi.mocked(fetch).mock.calls[0]?.[1]?.body).toBe(JSON.stringify({ decision: "REJECT" }));
  });

  it("on 409 explains, locks the buttons and asks the page to refetch", async () => {
    vi.mocked(fetch).mockResolvedValue(problem(409, "approval was already decided"));
    const onSettled = renderCard();
    fireEvent.click(screen.getByRole("button", { name: /Approve/ }));
    await waitFor(() => {
      expect(screen.getByText(/already decided or has expired/)).toBeInTheDocument();
    });
    expect(onSettled).toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Reject" })).toBeDisabled();
  });

  it("shows other failures with the server's fixed detail text", async () => {
    vi.mocked(fetch).mockResolvedValue(problem(503, "service unavailable"));
    renderCard();
    fireEvent.click(screen.getByRole("button", { name: /Approve/ }));
    await waitFor(() => {
      expect(screen.getByText(/orchestrator is starting/)).toBeInTheDocument();
    });
  });

  it("disables both buttons once the approval has expired", () => {
    renderCard();
    act(() => {
      vi.setSystemTime(new Date("2026-10-01T10:05:01Z"));
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByText("(expired)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Approve/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reject" })).toBeDisabled();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("hides Approve and Reject for a reader token and says why", () => {
    renderCard(vi.fn(), READER_ME);
    expect(screen.queryByRole("button", { name: /Approve/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reject" })).not.toBeInTheDocument();
    expect(screen.getByText(/needs an operator token/)).toBeInTheDocument();
  });
});
