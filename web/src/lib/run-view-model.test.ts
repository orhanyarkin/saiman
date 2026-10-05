import { describe, expect, it } from "vitest";

import type { RunEvent } from "@/lib/api/run-events";
import { basescanTxUrl, deriveRunView, describeEvent, safeKapUrl } from "@/lib/run-view-model";

const RUN = "6ad4354c-8e79-4b49-b5d9-d45eb9689b41";
const USDC = (atomicUnits: number) => ({ atomicUnits, asset: "USDC", decimals: 6 });
let seq = 0;

function ev<T extends RunEvent["type"]>(
  type: T,
  data: Extract<RunEvent, { type: T }>["data"],
): RunEvent {
  seq += 1;
  return {
    eventId: `${RUN}:${String(seq)}`,
    runId: RUN,
    seq,
    type,
    occurredAt: "2026-10-01T10:00:00Z",
    data,
  } as RunEvent;
}

const required = {
  approvalId: "a1",
  paymentIntentId: "p1",
  amount: USDC(20000),
  payTo: "0x1111111111111111111111111111111111111111",
  resource: "http://seller/x",
  expiresAt: "2026-10-01T10:05:00Z",
};

describe("deriveRunView", () => {
  it("tracks steps and the budget", () => {
    seq = 0;
    const view = deriveRunView([
      ev("RUN_STARTED", { question: "q", budget: USDC(50000) }),
      ev("STEP_STARTED", { step: "PLANNER" }),
      ev("STEP_COMPLETED", { step: "PLANNER" }),
      ev("STEP_STARTED", { step: "RESEARCHER" }),
    ]);
    expect(view.steps).toEqual({
      PLANNER: "done",
      RESEARCHER: "running",
      RISK: "pending",
      SYNTHESIS: "pending",
    });
    expect(view.currentStep).toBe("RESEARCHER");
    expect(view.budget?.atomicUnits).toBe(50000);
  });

  it("surfaces an unresolved approval and clears it once decided", () => {
    seq = 0;
    const open = [ev("PAYMENT_APPROVAL_REQUIRED", required)];
    expect(deriveRunView(open).pendingApproval?.approvalId).toBe("a1");
    expect(deriveRunView(open).payments[0]?.state).toBe("awaiting-approval");

    const decided = [
      ...open,
      ev("PAYMENT_APPROVAL_DECIDED", { approvalId: "a1", decision: "APPROVED" }),
    ];
    expect(deriveRunView(decided).pendingApproval).toBeNull();
    expect(deriveRunView(decided).payments[0]?.state).toBe("approved");
  });

  it("settles a payment row and records denials", () => {
    seq = 0;
    const view = deriveRunView([
      ev("PAYMENT_APPROVAL_REQUIRED", required),
      ev("PAYMENT_SETTLED", { paymentIntentId: "p1", amount: USDC(20000), txHash: "0xabc" }),
      ev("PAYMENT_DENIED", { reason: "RUN_BUDGET", amount: USDC(20000) }),
    ]);
    expect(view.payments.map((p) => p.state)).toEqual(["settled", "denied"]);
    expect(view.payments[0]?.txHash).toBe("0xabc");
  });

  it("never shows a pending approval on a finished run", () => {
    seq = 0;
    const cost = { paymentsUsdc: USDC(0), llmUsd: USDC(0), totalUsd: USDC(0) };
    const view = deriveRunView([
      ev("PAYMENT_APPROVAL_REQUIRED", required),
      ev("RUN_FAILED", { failureCode: "RUN_DEADLINE", costSoFar: cost }),
    ]);
    expect(view.pendingApproval).toBeNull();
    expect(view.terminal).toBe(true);
    expect(view.failureCode).toBe("RUN_DEADLINE");
  });
});

describe("describeEvent", () => {
  it("formats a model call with tokens and USD", () => {
    seq = 0;
    const item = describeEvent(
      ev("MODEL_CALL_COMPLETED", {
        step: "SYNTHESIS",
        tier: "tier1",
        model: "m",
        inputTokens: 1200,
        outputTokens: 340,
        costUsd: { atomicUnits: 6923, asset: "USD", decimals: 6 },
      }),
    );
    expect(item.detail).toContain("1200 in / 340 out");
    expect(item.detail).toContain("$0.0069");
  });
});

describe("link safety", () => {
  it.each([
    ["https://www.kap.org.tr/tr/Bildirim/1118495", true],
    ["http://www.kap.org.tr/tr/Bildirim/1", false],
    ["https://evil.example/https://www.kap.org.tr/", false],
    ["https://www.kap.org.tr@evil.example/x", false],
    ["https://www.kap.org.tr.evil.example/x", false],
    ["javascript:alert(1)", false],
    ["", false],
  ])("safeKapUrl(%s) linkable=%s", (url, linkable) => {
    expect(safeKapUrl(url) !== null).toBe(linkable);
  });

  it("builds a Basescan link only for a real tx hash", () => {
    const hash = `0x${"a".repeat(64)}`;
    expect(basescanTxUrl(hash)).toBe(`https://sepolia.basescan.org/tx/${hash}`);
    expect(basescanTxUrl("0x123")).toBeNull();
    expect(basescanTxUrl(`${hash}/../x`)).toBeNull();
  });
});
