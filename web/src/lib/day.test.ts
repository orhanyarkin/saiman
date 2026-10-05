import { describe, expect, it } from "vitest";

import { isValidDay, todayUtc } from "@/lib/day";
import { remainingMoney, usdc } from "@/lib/money";

describe("day", () => {
  it("formats today in UTC, not local time", () => {
    expect(todayUtc(new Date("2026-10-05T23:59:59Z"))).toBe("2026-10-05");
    expect(todayUtc(new Date("2026-10-06T00:00:00Z"))).toBe("2026-10-06");
  });

  it.each(["2026-10-05", "2024-02-29"])("accepts %s", (day) => {
    expect(isValidDay(day)).toBe(true);
  });

  it.each(["", "2026-13-01", "2026-02-30", "2025-02-29", "26-10-05", "2026-10-5", "today"])(
    "rejects %j",
    (day) => {
      expect(isValidDay(day)).toBe(false);
    },
  );
});

describe("remainingMoney", () => {
  it("subtracts in integers and clamps at zero", () => {
    expect(remainingMoney(usdc(1_000_000), usdc(10_000), usdc(50_000))).toEqual(usdc(940_000));
    expect(remainingMoney(usdc(10), usdc(50))).toEqual(usdc(0));
  });

  it("refuses unsafe integers and mixed assets", () => {
    expect(remainingMoney(usdc(Number.MAX_SAFE_INTEGER + 2), usdc(1))).toBeNull();
    expect(remainingMoney(usdc(10), { atomicUnits: 1, asset: "USD", decimals: 6 })).toBeNull();
  });
});
