import { describe, expect, it } from "vitest";

import { normalizeCapture, parseAnnotations, parseCorpus } from "@/lib/api/replay";
import { corpusBannerText, formatSnapshotDate } from "@/lib/corpus";

const base = {
  schemaVersion: 1,
  capturedAt: "2026-10-01T10:00:00Z",
  environment: "AWS eu-central-1",
  responses: {},
};
const corpus = {
  snapshotLabel: "KAP disclosures up to 2023-12-29 (frozen MKK snapshot, ADR-0010)",
  newestDisclosureAt: "2023-12-29T20:46:52Z",
};
const note = { label: "Expected failure", detail: "Newer than the corpus.", tone: "warning" };

describe("optional corpus and annotations", () => {
  it("are absent-safe: old captures normalise to null and empty", () => {
    const capture = normalizeCapture(base);
    expect(capture.corpus).toBeNull();
    expect(Object.keys(capture.annotations)).toEqual([]);
  });

  it("are read when valid", () => {
    const capture = normalizeCapture({ ...base, corpus, annotations: { run1: note } });
    expect(capture.corpus).toEqual(corpus);
    expect(capture.annotations.run1).toEqual(note);
  });

  it.each([
    null,
    "text",
    [],
    {},
    { snapshotLabel: "x" },
    { snapshotLabel: "", newestDisclosureAt: "2023-12-29T00:00:00Z" },
    { snapshotLabel: "x", newestDisclosureAt: "not a date" },
    { snapshotLabel: 5, newestDisclosureAt: "2023-12-29T00:00:00Z" },
    { snapshotLabel: "x".repeat(500), newestDisclosureAt: "2023-12-29T00:00:00Z" },
  ])("ignores a malformed corpus %#", (value) => {
    expect(parseCorpus(value)).toBeNull();
    expect(() => normalizeCapture({ ...base, corpus: value })).not.toThrow();
  });

  it("drops malformed annotation entries one by one and never throws", () => {
    const parsed = parseAnnotations({
      ok: note,
      noTone: { label: "a", detail: "b" },
      badTone: { ...note, tone: "danger" },
      noDetail: { label: "a" },
      long: { ...note, detail: "x".repeat(2000) },
      str: "nope",
      __proto__: note,
    });
    expect(Object.keys(parsed)).toContain("ok");
    expect(Object.keys(parsed)).not.toContain("noTone");
    expect(Object.keys(parsed)).not.toContain("badTone");
    expect(Object.keys(parsed)).not.toContain("noDetail");
    expect(Object.keys(parsed)).not.toContain("long");
    expect(Object.keys(parsed)).not.toContain("str");
    for (const value of [null, 3, "x", [], [note]]) {
      expect(Object.keys(parseAnnotations(value))).toEqual([]);
    }
  });
});

describe("corpus banner text", () => {
  it("formats the newest disclosure date en-GB in UTC", () => {
    expect(formatSnapshotDate("2023-12-29T20:46:52Z")).toBe("29 Dec 2023");
    expect(formatSnapshotDate("2024-01-01T00:30:00+03:00")).toBe("31 Dec 2023");
    expect(formatSnapshotDate("nope")).toBeNull();
  });

  it("builds the sentence, or nothing without a corpus", () => {
    expect(corpusBannerText(corpus)).toBe(
      "Answers come from a frozen KAP snapshot: disclosures up to 29 Dec 2023.",
    );
    expect(corpusBannerText(null)).toBeNull();
    expect(corpusBannerText({ newestDisclosureAt: "bad" })).toBeNull();
  });
});
