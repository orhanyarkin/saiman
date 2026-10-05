/**
 * The replay data source (ADR-0004, ADR-0026): answers `apiGet` from a recorded capture and replays
 * a run's events with their original gaps. Screens never see the difference.
 *
 * Capture format v1:
 *   { schemaVersion: 1, capturedAt, environment, network, sourceCommit,
 *     responses: { "<GET path+query>": body }, runEvents: { "<runId>": [Envelope] } }
 * Optional: corpus { snapshotLabel, newestDisclosureAt }, annotations { "<runId>": { label, detail, tone } }.
 * The M5 fixture shape (no `schemaVersion`, no `network`/`sourceCommit`) is accepted too.
 */
import { ApiError } from "@/lib/api/errors";
import { parseRunEvents, type RunEvent } from "@/lib/api/run-events";
import type { Me } from "@/lib/api/types";

export interface Capture {
  schemaVersion: 1;
  capturedAt: string;
  environment: string;
  network: string;
  sourceCommit: string | null;
  responses: Record<string, unknown>;
  runEvents: Record<string, RunEvent[]>;
  /** Optional: which KAP snapshot the answers come from. Null when absent or malformed. */
  corpus: CorpusSnapshot | null;
  /** Optional: per-run notes (`label`, `detail`, `tone`), keyed by run id. Plain text only. */
  annotations: Record<string, RunAnnotation>;
}

export interface CorpusSnapshot {
  snapshotLabel: string;
  /** ISO-8601 instant of the newest disclosure in the corpus. */
  newestDisclosureAt: string;
}

export interface RunAnnotation {
  label: string;
  detail: string;
  tone: "warning" | "info";
}

const MAX_LABEL = 120;
const MAX_DETAIL = 600;

const isText = (v: unknown, max: number): v is string =>
  typeof v === "string" && v.trim() !== "" && v.length <= max;

/** `corpus` or null; a malformed value is ignored, never thrown on. */
export function parseCorpus(value: unknown): CorpusSnapshot | null {
  if (!isRecord(value)) {
    return null;
  }
  const { snapshotLabel, newestDisclosureAt } = value;
  if (
    !isText(snapshotLabel, MAX_LABEL) ||
    typeof newestDisclosureAt !== "string" ||
    Number.isNaN(Date.parse(newestDisclosureAt))
  ) {
    return null;
  }
  return { snapshotLabel, newestDisclosureAt };
}

/** Valid annotations only; each malformed entry is dropped on its own. No prototype, like runEvents. */
export function parseAnnotations(value: unknown): Record<string, RunAnnotation> {
  const out: Record<string, RunAnnotation> = Object.create(null) as Record<string, RunAnnotation>;
  if (!isRecord(value)) {
    return out;
  }
  for (const [runId, raw] of Object.entries(value)) {
    if (
      isRecord(raw) &&
      isText(raw.label, MAX_LABEL) &&
      isText(raw.detail, MAX_DETAIL) &&
      (raw.tone === "warning" || raw.tone === "info")
    ) {
      Object.defineProperty(out, runId, {
        value: { label: raw.label, detail: raw.detail, tone: raw.tone },
        enumerable: true,
        writable: true,
        configurable: true,
      });
    }
  }
  return out;
}

export const DEFAULT_CAPTURE_URL = "/demo/capture.json";
/** Gaps between replayed events are clamped to this range. */
export const MIN_GAP_MS = 50;
export const MAX_GAP_MS = 2000;

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);

/** Validates and normalises a parsed capture (v1 or the older fixture shape). Throws on garbage. */
export function normalizeCapture(json: unknown): Capture {
  if (!isRecord(json)) {
    throw new Error("The recording is not a JSON object.");
  }
  if (json.schemaVersion !== undefined && json.schemaVersion !== 1) {
    throw new Error("Unsupported recording version.");
  }
  if (typeof json.capturedAt !== "string" || Number.isNaN(Date.parse(json.capturedAt))) {
    throw new Error("The recording has no valid capturedAt.");
  }
  if (typeof json.environment !== "string" || json.environment.trim() === "") {
    throw new Error("The recording has no environment.");
  }
  if (!isRecord(json.responses)) {
    throw new Error("The recording has no responses.");
  }
  // No prototype: a run id such as "__proto__" is then just a key, never Object.prototype.
  const runEvents: Record<string, RunEvent[]> = Object.create(null) as Record<string, RunEvent[]>;
  if (isRecord(json.runEvents)) {
    for (const [runId, events] of Object.entries(json.runEvents)) {
      Object.defineProperty(runEvents, runId, {
        value: parseRunEvents(events),
        enumerable: true,
        writable: true,
        configurable: true,
      });
    }
  }
  return {
    schemaVersion: 1,
    capturedAt: json.capturedAt,
    environment: json.environment,
    network: typeof json.network === "string" ? json.network : "base-sepolia",
    sourceCommit: typeof json.sourceCommit === "string" ? json.sourceCommit : null,
    responses: json.responses,
    runEvents,
    corpus: parseCorpus(json.corpus),
    annotations: parseAnnotations(json.annotations),
  };
}

let loaded: Capture | null = null;
let loading: Promise<Capture> | null = null;

function captureUrl(): string {
  const configured = import.meta.env.VITE_REPLAY_CAPTURE_URL;
  return configured !== undefined && configured !== "" ? configured : DEFAULT_CAPTURE_URL;
}

/** Fetches the capture once (no credentials, no token: it is a public static file). */
export function loadCapture(): Promise<Capture> {
  loading ??= fetch(captureUrl(), { headers: { Accept: "application/json" } })
    .then((response) => {
      if (!response.ok) {
        throw new Error(`The recording could not be loaded (HTTP ${String(response.status)}).`);
      }
      return response.json() as Promise<unknown>;
    })
    .then((json) => {
      loaded = normalizeCapture(json);
      return loaded;
    })
    .catch((error: unknown) => {
      loading = null; // allow a retry on the next call
      throw error instanceof Error ? error : new Error("The recording could not be loaded.");
    });
  return loading;
}

/** The capture if it has been loaded, else null (the banner shows a fallback until then). */
export function getLoadedCapture(): Capture | null {
  return loaded;
}

/** Tests only. */
export function resetCaptureForTests(capture: Capture | null = null): void {
  loaded = capture;
  loading = capture === null ? null : Promise.resolve(capture);
}

/** `/api/v1/me` in a recording: always a reader (nothing can be changed). */
export const REPLAY_ME: Me = { name: "recorded-demo", roles: ["READER"] };

function notInRecording(path: string): ApiError {
  return new ApiError(
    "not-found",
    404,
    `GET ${path} failed: not in this recording`,
    "This is not in this recording.",
  );
}

/**
 * The request key with its `limit` parameter removed, for matching a first page captured with a
 * different page size. Null for a cursor request (`before`), which must match exactly.
 */
function withoutLimit(path: string): string | null {
  const [bare = path, query = ""] = path.split("?");
  const params = new URLSearchParams(query);
  if (params.has("before")) {
    return null;
  }
  params.delete("limit");
  const rest = params.toString();
  return rest === "" ? bare : `${bare}?${rest}`;
}

/** Resolves a GET from the capture: exact path+query, then path alone, then the run event export. */
export async function replayGet<T>(path: string): Promise<T> {
  const bare = path.split("?")[0] ?? path;
  if (bare === "/api/v1/me") {
    return REPLAY_ME as T;
  }
  let capture: Capture;
  try {
    capture = await loadCapture();
  } catch (cause) {
    throw new ApiError(
      "network",
      0,
      `GET ${path} failed: ${cause instanceof Error ? cause.message : "recording unavailable"}`,
      "The recording could not be loaded.",
    );
  }
  const { responses } = capture;
  const hit = Object.hasOwn(responses, path) ? path : Object.hasOwn(responses, bare) ? bare : null;
  if (hit !== null) {
    return structuredClone(responses[hit]) as T;
  }
  const sameLimitless = withoutLimit(path);
  if (sameLimitless !== null) {
    // A first page captured with another page size still answers (the recording is a snapshot).
    const key = Object.keys(responses).find((k) => withoutLimit(k) === sameLimitless);
    if (key !== undefined) {
      return structuredClone(responses[key]) as T;
    }
  }
  const events = /^\/api\/v1\/runs\/([^/]+)\/events$/.exec(bare);
  if (events?.[1] !== undefined) {
    const list = Object.hasOwn(capture.runEvents, events[1]) ? capture.runEvents[events[1]] : null;
    if (list) {
      return structuredClone(list) as T;
    }
  }
  throw notInRecording(path);
}

export const clampGap = (ms: number): number => Math.min(MAX_GAP_MS, Math.max(MIN_GAP_MS, ms));

/** Delays between consecutive events (the first one gets the minimum): original gaps, clamped. */
export function replayDelays(events: readonly RunEvent[]): number[] {
  return events.map((event, index) => {
    const previous = events[index - 1];
    if (previous === undefined) {
      return MIN_GAP_MS;
    }
    return clampGap(Date.parse(event.occurredAt) - Date.parse(previous.occurredAt));
  });
}

export interface ReplayHandlers {
  onEvent: (event: RunEvent) => void;
  onClose: () => void;
}

/**
 * Plays a recorded run's events with their original relative timing. Ends (`onClose`) after the
 * last event, or at once when the run is not in the recording.
 */
export function replayRunEvents(runId: string, handlers: ReplayHandlers): { close(): void } {
  let closed = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  void loadCapture().then(
    (capture) => {
      const events = Object.hasOwn(capture.runEvents, runId)
        ? (capture.runEvents[runId] ?? [])
        : [];
      const delays = replayDelays(events);
      let index = 0;
      const next = () => {
        if (closed) {
          return;
        }
        const event = events[index];
        if (event === undefined) {
          closed = true;
          handlers.onClose();
          return;
        }
        timer = setTimeout(() => {
          if (closed) {
            return;
          }
          handlers.onEvent(event);
          index++;
          next();
        }, delays[index]);
      };
      next();
    },
    () => {
      if (!closed) {
        closed = true;
        handlers.onClose();
      }
    },
  );

  return {
    close() {
      closed = true;
      if (timer !== null) {
        clearTimeout(timer);
      }
    },
  };
}

/** The annotation of a run in the loaded recording; null in live mode or when there is none. */
export function annotationFor(runId: string): RunAnnotation | null {
  return loaded?.annotations[runId] ?? null;
}
