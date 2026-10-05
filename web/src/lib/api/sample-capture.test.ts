// @vitest-environment node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { normalizeCapture } from "@/lib/api/replay";

describe("sample capture fixture", () => {
  const text = readFileSync(
    resolve(import.meta.dirname, "../../../e2e/fixtures/replay-capture.sample.json"),
    "utf8",
  );

  it("is a valid capture v1 clearly marked as sample data", () => {
    const capture = normalizeCapture(JSON.parse(text));
    expect(capture.environment).toMatch(/SAMPLE DATA/);
  });

  it("contains no internal service hosts (http://<service>:<port>)", () => {
    expect(text.match(/http:\/\/[A-Za-z0-9_.-]+:\d+/g) ?? []).toEqual([]);
  });
});
