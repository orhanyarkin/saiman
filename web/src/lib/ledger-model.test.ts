import { describe, expect, it } from "vitest";

import type { RunEvent } from "@/lib/api/run-events";
import type { PaymentSummary, TrialBalanceRow } from "@/lib/api/types";
import {
  allPaymentsBooked,
  checkTrialBalance,
  impliedEntryCount,
  isAddress,
  isUuid,
  ledgerArmTime,
  ledgerPollPhase,
  LEDGER_POLL_WINDOW_MS,
  mismatchText,
} from "@/lib/ledger-model";
import { formatBigMoney } from "@/lib/money";

const payment = (buyerState: string, sellerState: string): PaymentSummary => ({
  paymentId: "3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e51",
  runId: null,
  payTo: "0x1111111111111111111111111111111111111111",
  amount: { atomicUnits: 20000, asset: "USDC", decimals: 6 },
  buyerState,
  sellerState,
  chainState: "UNKNOWN",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
});

const row = (book: string, debit: number, credit: number, account = "a"): TrialBalanceRow => ({
  book,
  account,
  type: "ASSET",
  asset: "USDC",
  decimals: 6,
  debit,
  credit,
  balance: debit - credit,
});

describe("route parameter validation", () => {
  it("accepts UUIDs only", () => {
    expect(isUuid("3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e51")).toBe(true);
    expect(isUuid("not-a-uuid")).toBe(false);
    expect(isUuid("3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e51/../x")).toBe(false);
    expect(isUuid("")).toBe(false);
  });

  it("accepts full 0x addresses only", () => {
    expect(isAddress("0x1111111111111111111111111111111111111111")).toBe(true);
    expect(isAddress("0x1111")).toBe(false);
    expect(isAddress("1111111111111111111111111111111111111111")).toBe(false);
  });
});

describe("entries derived from the book states", () => {
  it("matches the ledger's implied entries", () => {
    expect(impliedEntryCount(payment("NONE", "NONE"))).toBe(0);
    expect(impliedEntryCount(payment("AUTHORIZED", "NONE"))).toBe(1);
    expect(impliedEntryCount(payment("SETTLED", "SETTLED"))).toBe(3);
    expect(impliedEntryCount(payment("RELEASED", "NONE"))).toBe(2);
    expect(impliedEntryCount(payment("SETTLED", "CREDITED"))).toBe(4);
    expect(impliedEntryCount(payment("SOMETHING_NEW", "SETTLED"))).toBe(1);
  });

  it("is complete only when every payment is booked in both books", () => {
    expect(allPaymentsBooked([])).toBe(false);
    expect(allPaymentsBooked([payment("SETTLED", "NONE")])).toBe(false);
    expect(allPaymentsBooked([payment("SETTLED", "SETTLED"), payment("AUTHORIZED", "NONE")])).toBe(
      false,
    );
    expect(allPaymentsBooked([payment("SETTLED", "SETTLED")])).toBe(true);
  });
});

describe("ledger poll window", () => {
  const settled = [payment("SETTLED", "SETTLED")];
  it("is idle until a payment event armed it", () => {
    expect(ledgerPollPhase(null, 1_000, [])).toBe("idle");
  });
  it("polls for 60 s, then gives up", () => {
    expect(ledgerPollPhase(0, 1_000, [])).toBe("polling");
    expect(ledgerPollPhase(0, LEDGER_POLL_WINDOW_MS - 1, [])).toBe("polling");
    expect(ledgerPollPhase(0, LEDGER_POLL_WINDOW_MS, [])).toBe("gave-up");
  });
  it("stops early once everything is booked", () => {
    expect(ledgerPollPhase(0, 1_000, settled)).toBe("complete");
    expect(ledgerPollPhase(0, 1_000, [payment("SETTLED", "NONE")])).toBe("polling");
  });
});

describe("ledgerArmTime", () => {
  const event = (type: string, occurredAt: string, seq: number) =>
    ({ eventId: String(seq), runId: "r", seq, type, occurredAt, data: {} }) as unknown as RunEvent;
  it("is null without a payment event", () => {
    expect(ledgerArmTime([event("RUN_STARTED", "2026-10-01T10:00:00Z", 1)])).toBeNull();
  });
  it("re-arms on the terminal event after the payment events", () => {
    const events = [
      event("PAYMENT_APPROVAL_REQUIRED", "2026-10-01T10:00:00Z", 1),
      event("PAYMENT_SETTLED", "2026-10-01T10:04:00Z", 2),
      event("RUN_COMPLETED", "2026-10-01T10:04:10Z", 3),
    ];
    expect(ledgerArmTime(events)).toBe(Date.parse("2026-10-01T10:04:10Z"));
    expect(ledgerArmTime(events.slice(0, 2))).toBe(Date.parse("2026-10-01T10:04:00Z"));
  });
});

describe("trial balance check", () => {
  it("balances per book and asset", () => {
    const checks = checkTrialBalance([
      row("BUYER", 100, 0),
      row("BUYER", 0, 100),
      row("SELLER", 50, 0),
      row("SELLER", 0, 40),
    ]);
    expect(checks.map((c) => [c.book, c.status])).toEqual([
      ["BUYER", "balanced"],
      ["SELLER", "unbalanced"],
    ]);
  });

  it("refuses when any amount lost precision (not a safe integer)", () => {
    const [check] = checkTrialBalance([row("BUYER", 2 ** 53 + 2, 0), row("BUYER", 0, 2 ** 53 + 2)]);
    expect(check?.status).toBe("unverifiable");
    expect(check?.debits).toBeNull();
  });

  it("sums safe integers exactly beyond 2^53 with BigInt", () => {
    const max = Number.MAX_SAFE_INTEGER;
    const [check] = checkTrialBalance([
      row("BUYER", max, 0, "a"),
      row("BUYER", max, 0, "b"),
      row("BUYER", 0, max, "c"),
      row("BUYER", 0, max, "d"),
    ]);
    expect(check?.status).toBe("balanced");
    expect(check?.debits).toBe(18014398509481982n);
    expect(formatBigMoney(check?.debits ?? 0n, "USDC", 6)).toBe("18,014,398,509.481982 USDC");
  });
});

describe("mismatchText", () => {
  it("explains the uncorroborated credit note kind the API returns", () => {
    expect(mismatchText("CREDIT_NOTE_UNCORROBORATED")).toBe(
      "A credit note was booked that the seller does not confirm.",
    );
  });

  it("falls back to a generic finding for an unknown kind (ADR: consumers must tolerate new kinds)", () => {
    expect(mismatchText("SOMETHING_NEW")).toBe("Reconciliation finding: SOMETHING_NEW.");
  });
});
