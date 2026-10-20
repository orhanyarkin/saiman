import { describe, expect, it } from "vitest";

import { bannerText, captureDate, environmentLabel, liveBannerText } from "@/lib/banner-text";

const UNKNOWN = "Testnet only. No real money moves.";

describe("liveBannerText", () => {
  it("names a known environment", () => {
    expect(liveBannerText("local")).toBe("Testnet only, local demo. No real money moves.");
    expect(liveBannerText("aws-demo")).toBe("Testnet only, AWS demo. No real money moves.");
  });

  it.each([
    ["an empty string, the orchestrator's default", ""],
    ["whitespace", "   "],
    ["a missing field", undefined],
    ["a failed ping", null],
    ["a value outside the vocabulary", "staging-7"],
    ["a value that only looks local", "localhost"],
  ])("claims no environment for %s", (_case, value) => {
    expect(liveBannerText(value)).toBe(UNKNOWN);
  });

  it("never says local unless the orchestrator said local", () => {
    for (const value of ["", "   ", "aws-demo", "staging-7", undefined, null]) {
      expect(liveBannerText(value)).not.toContain("local demo");
    }
  });
});

describe("environmentLabel", () => {
  it("is null for anything the UI cannot name", () => {
    expect(environmentLabel("local")).toBe("local demo");
    expect(environmentLabel(" aws-demo ")).toBe("AWS demo");
    expect(environmentLabel("")).toBeNull();
    expect(environmentLabel(undefined)).toBeNull();
  });
});

describe("bannerText", () => {
  it("still renders the capture's own environment verbatim", () => {
    expect(
      bannerText({ environment: "AWS eu-central-1", capturedAt: "2026-10-01T10:00:00Z" }),
    ).toBe(
      "Recorded on AWS eu-central-1, 2026-10-01 - Base Sepolia testnet. Nothing on this page is live.",
    );
    expect(bannerText(null)).toBe(
      "Recorded demo - Base Sepolia testnet. Nothing on this page is live.",
    );
  });

  it("falls back to a readable date when the capture time is unusable", () => {
    expect(captureDate("not a date")).toBe("unknown date");
  });
});
