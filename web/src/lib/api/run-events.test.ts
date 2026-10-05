import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import {
  isTerminalEvent,
  mergeEvents,
  parseRunEvent,
  parseRunEvents,
  RUN_EVENT_TYPES,
} from "@/lib/api/run-events";

// Vitest runs with the web/ directory as cwd.
const FIXTURE_DIR = `${resolve(process.cwd(), "../libs/shared/src/test/resources/fixtures/events/agent.run-step.v1")}/`;

const fixtures = readdirSync(FIXTURE_DIR)
  .filter((name) => name.endsWith(".json"))
  .map((name) => ({
    name,
    json: JSON.parse(readFileSync(FIXTURE_DIR + name, "utf8")) as Record<string, unknown>,
  }));

function clone(json: Record<string, unknown>): Record<string, unknown> {
  return structuredClone(json);
}

describe("shared golden fixtures", () => {
  it("covers every event type exactly once", () => {
    expect(fixtures.map((f) => f.name.replace(".json", "")).sort()).toEqual(
      [...RUN_EVENT_TYPES].sort(),
    );
  });

  it.each(fixtures)("parses $name", ({ name, json }) => {
    const event = parseRunEvent(json);
    expect(event).not.toBeNull();
    expect(event?.type).toBe(name.replace(".json", ""));
  });

  it.each(fixtures)("rejects a mutated $name", ({ json }) => {
    const wrongType = { ...clone(json), type: "NOT_A_TYPE" };
    expect(parseRunEvent(wrongType)).toBeNull();

    const noData = { ...clone(json), data: null };
    expect(parseRunEvent(noData)).toBeNull();

    const badSeq = { ...clone(json), seq: 0 };
    expect(parseRunEvent(badSeq)).toBeNull();

    const mismatchedId = { ...clone(json), eventId: "other:1" };
    expect(parseRunEvent(mismatchedId)).toBeNull();

    const data = clone(json).data as Record<string, unknown>;
    for (const field of Object.keys(data)) {
      const broken = { ...clone(json), data: { ...data, [field]: { nonsense: true } } };
      expect(parseRunEvent(broken), `field ${field}`).toBeNull();
      const missing = Object.fromEntries(Object.entries(data).filter(([key]) => key !== field));
      expect(parseRunEvent({ ...clone(json), data: missing }), `missing ${field}`).toBeNull();
    }
  });
});

describe("parseRunEvent", () => {
  it.each([[null], [undefined], ["text"], [42], [[]], [{}]])("rejects %j", (value) => {
    expect(parseRunEvent(value)).toBeNull();
  });

  it("rejects a fractional money amount and an unknown enum value", () => {
    const started = fixtures.find((f) => f.name === "RUN_STARTED.json")?.json;
    const denied = fixtures.find((f) => f.name === "PAYMENT_DENIED.json")?.json;
    expect(started).toBeDefined();
    expect(denied).toBeDefined();
    const fractional = clone(started ?? {});
    (fractional.data as { budget: { atomicUnits: number } }).budget.atomicUnits = 0.5;
    expect(parseRunEvent(fractional)).toBeNull();
    const unknownReason = clone(denied ?? {});
    (unknownReason.data as { reason: string }).reason = "SOMETHING_NEW";
    expect(parseRunEvent(unknownReason)).toBeNull();
  });

  it("flags only RUN_COMPLETED and RUN_FAILED as terminal", () => {
    const terminal = fixtures
      .map((f) => parseRunEvent(f.json))
      .filter((e) => e !== null && isTerminalEvent(e))
      .map((e) => e?.type)
      .sort();
    expect(terminal).toEqual(["RUN_COMPLETED", "RUN_FAILED"]);
  });
});

describe("mergeEvents / parseRunEvents", () => {
  const events = fixtures
    .map((f) => parseRunEvent(f.json))
    .filter((e) => e !== null)
    .sort((a, b) => a.seq - b.seq);

  it("dedupes by seq and keeps ascending order", () => {
    const first = events.slice(0, 5);
    const overlapping = [...events.slice(3), ...events.slice(0, 2)];
    const merged = mergeEvents(first, overlapping);
    expect(merged.map((e) => e.seq)).toEqual(events.map((e) => e.seq));
  });

  it("drops invalid entries from an export and tolerates non-arrays", () => {
    expect(parseRunEvents([events[0], { type: "bogus" }, "x"])).toHaveLength(1);
    expect(parseRunEvents({ not: "an array" })).toEqual([]);
  });
});
