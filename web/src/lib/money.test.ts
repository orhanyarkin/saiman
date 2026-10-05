import { describe, expect, it } from "vitest";

import { formatMoney, moneyTitle, parseUsdcInput, UNAVAILABLE, usdcInputValue } from "@/lib/money";

const usdc = (atomicUnits: number) => ({ atomicUnits, asset: "USDC", decimals: 6 });
const usd = (atomicUnits: number) => ({ atomicUnits, asset: "USD", decimals: 6 });

describe("formatMoney", () => {
  it.each([
    [usdc(0), "0.00 USDC"],
    [usdc(20000), "0.02 USDC"],
    [usdc(50000), "0.05 USDC"],
    [usdc(1), "0.000001 USDC"],
    [usdc(36923), "0.036923 USDC"],
    [usdc(1_000_000), "1.00 USDC"],
    [usdc(1_234_567_890_000), "1,234,567.89 USDC"],
    [usdc(-20000), "-0.02 USDC"],
    [usd(6923), "$0.0069"],
    [usd(1850), "$0.0019"],
    [usd(0), "$0.0000"],
    [usd(999_960), "$1.0000"],
    [usd(12_345_678), "$12.3457"],
    [{ atomicUnits: 7, asset: "EUR", decimals: 0 }, "7 EUR"],
  ])("formats %j as %s", (money, expected) => {
    expect(formatMoney(money)).toBe(expected);
  });

  it.each([
    [usdc(Number.MAX_SAFE_INTEGER + 2)],
    [usdc(1.5)],
    [usdc(Number.NaN)],
    [{ atomicUnits: 1, asset: "USDC", decimals: -1 }],
  ])("refuses %j", (money) => {
    expect(formatMoney(money)).toBe(UNAVAILABLE);
    expect(moneyTitle(money)).toMatch(/too large/);
  });

  it("explains the exact integer in the title", () => {
    expect(moneyTitle(usdc(20000))).toBe("20000 atomic units of USDC (6 decimals)");
  });
});

describe("parseUsdcInput / usdcInputValue", () => {
  it.each([
    ["0.05", 50000],
    ["1", 1_000_000],
    [" 0.000001 ", 1],
    ["12.5", 12_500_000],
  ])("parses %s", (text, atomic) => {
    expect(parseUsdcInput(text)).toBe(atomic);
  });

  it.each([[""], ["abc"], ["0.0000001"], ["-1"], ["1e3"], ["1,5"], [".5"]])(
    "rejects %j",
    (text) => {
      expect(parseUsdcInput(text)).toBeNull();
    },
  );

  it.each([
    [50000, "0.05"],
    [1_000_000, "1"],
    [1, "0.000001"],
  ])("renders %d as an input value", (atomic, text) => {
    expect(usdcInputValue(atomic)).toBe(text);
  });
});
