/**
 * Builds `public/demo/capture.json`, the SAMPLE recording bundled with every build (ADR-0026), from
 * the e2e example captures. It is not a real recording: the `environment` field says so and the
 * replay banner shows it. A real recording replaces it (`CAPTURE_OUT=web/public/demo/capture.json
 * make capture-demo`). Run with `pnpm demo:sample`; Node >= 22.19 runs this TypeScript file as is.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { paymentItemsOf, summarizeRun } from "../e2e/fixture-server.ts";

const HERE = dirname(fileURLToPath(import.meta.url));
const read = (name: string) =>
  JSON.parse(readFileSync(resolve(HERE, "../e2e/fixtures", name), "utf8")) as {
    capturedAt: string;
    responses: Record<string, unknown>;
    runEvents?: Record<string, Parameters<typeof summarizeRun>[1]>;
  };

const orchestrator = read("capture.example.json");
const ledger = read("capture.ledger.example.json");
const responses: Record<string, unknown> = { ...orchestrator.responses, ...ledger.responses };
const runEvents = orchestrator.runEvents ?? {};

for (const [runId, events] of Object.entries(runEvents)) {
  responses[`/api/v1/runs/${runId}`] ??= summarizeRun(runId, events);
  responses[`/api/v1/runs/${runId}/payments`] ??= { items: paymentItemsOf(events) };
}
responses["/api/v1/approvals?status=PENDING"] ??= [];

const capture = {
  schemaVersion: 1,
  capturedAt: orchestrator.capturedAt,
  environment: "SAMPLE DATA from the e2e fixtures - not a real recording",
  network: "base-sepolia",
  sourceCommit: "sample",
  responses,
  runEvents,
};

const out = resolve(HERE, "../public/demo/capture.json");
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, `${JSON.stringify(capture, null, 2)}\n`);
console.log(`wrote ${out}`);
