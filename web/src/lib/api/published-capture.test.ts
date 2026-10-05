// @vitest-environment node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { normalizeCapture } from "@/lib/api/replay";

/** Smoke test of the REAL recording that ships with the site (public/demo/capture.json). */
describe("published capture", () => {
  const text = readFileSync(
    resolve(import.meta.dirname, "../../../public/demo/capture.json"),
    "utf8",
  );
  const json = JSON.parse(text) as { schemaVersion: number };
  const capture = normalizeCapture(json);

  it("is schema v1 with exactly three runs", () => {
    expect(json.schemaVersion).toBe(1);
    expect(Object.keys(capture.runEvents)).toHaveLength(3);
  });

  it("annotates the failed run with a 'Failed run' label", () => {
    const failed = Object.entries(capture.runEvents).filter(([, events]) =>
      events.some((e) => e.type === "RUN_FAILED"),
    );
    expect(failed).toHaveLength(1);
    const annotation = capture.annotations[failed[0]?.[0] ?? ""];
    expect(annotation?.label.startsWith("Failed run")).toBe(true);
    expect(annotation?.tone).toBe("warning");
  });

  it("names the frozen corpus snapshot", () => {
    expect(capture.corpus?.newestDisclosureAt).toBe("2023-12-29T20:46:52Z");
  });

  it("contains no internal hosts and no secret-looking fields", () => {
    const hosts = text.match(/https?:\/\/[A-Za-z0-9_.-]+(?::\d+)?/g) ?? [];
    const allowed = new Set(["https://demo.invalid", "https://www.kap.org.tr"]);
    expect(hosts.filter((h) => !allowed.has(h))).toEqual([]);
    for (const word of ["nonce", "signature", "paymentKey", "privateKey", "Bearer"]) {
      expect(text, word).not.toContain(word);
    }
  });
});
