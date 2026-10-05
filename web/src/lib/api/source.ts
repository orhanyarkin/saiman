/**
 * The single data source for screens: `apiGet` for every read, `apiPost` for the two mutations and
 * `subscribeRunEvents` for the live stream. Screens never call `fetch`/`EventSource` directly, so a
 * replay source (ADR-0004) can be swapped in here without touching them.
 *
 * Every request is same-origin (the dev/preview proxy or nginx routes `/api`), so there is no CORS
 * surface and no base URL to configure.
 */
import {
  isTerminalEvent,
  parseRunEvent,
  RUN_EVENT_TYPES,
  type RunEvent,
} from "@/lib/api/run-events";

/** True in the static replay build: mutations are hidden or disabled by the screens. */
export const isReplayMode = import.meta.env.VITE_DEMO_MODE === "replay";

export type ApiErrorKind =
  | "conflict" // 409
  | "rate-limited" // 429, see retryAfterSeconds
  | "not-ready" // 503 (orchestrator still starting)
  | "not-found" // 404
  | "invalid" // 400
  | "forbidden" // 403 (guard: Host, CSRF header, content type)
  | "replay" // mutation attempted in replay mode
  | "network"
  | "http";

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  /** RFC 9457 `detail`: fixed, server-authored text, safe to show as-is. */
  readonly detail: string | null;
  readonly retryAfterSeconds: number | null;

  constructor(
    kind: ApiErrorKind,
    status: number,
    message: string,
    detail: string | null = null,
    retryAfterSeconds: number | null = null,
  ) {
    super(message);
    this.name = "ApiError";
    this.kind = kind;
    this.status = status;
    this.detail = detail;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

function kindFor(status: number): ApiErrorKind {
  switch (status) {
    case 400:
      return "invalid";
    case 403:
      return "forbidden";
    case 404:
      return "not-found";
    case 409:
      return "conflict";
    case 429:
      return "rate-limited";
    case 503:
      return "not-ready";
    default:
      return "http";
  }
}

function parseRetryAfter(header: string | null): number | null {
  if (header === null || !/^\d{1,6}$/.test(header.trim())) {
    return null;
  }
  return Number(header.trim());
}

/** Turns a non-2xx response into an `ApiError`, reading Problem Details when present. */
async function toApiError(method: string, path: string, response: Response): Promise<ApiError> {
  let detail: string | null = null;
  try {
    const body: unknown = await response.json();
    if (typeof body === "object" && body !== null && "detail" in body) {
      const value = body.detail;
      detail = typeof value === "string" && value.length <= 300 ? value : null;
    }
  } catch {
    // No or non-JSON body: fall back to the status text.
  }
  return new ApiError(
    kindFor(response.status),
    response.status,
    `${method} ${path} failed: ${String(response.status)} ${response.statusText}`.trim(),
    detail,
    parseRetryAfter(response.headers.get("Retry-After")),
  );
}

async function request<T>(method: "GET" | "POST", path: string, init: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { ...init, method });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw cause;
    }
    throw new ApiError("network", 0, `${method} ${path} failed: network error`);
  }
  if (!response.ok) {
    throw await toApiError(method, path, response);
  }
  return (await response.json()) as T;
}

export function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  return request<T>("GET", path, {
    headers: { Accept: "application/json" },
    ...(signal ? { signal } : {}),
  });
}

/** POST with the headers the backend guard requires (`application/json` + `X-Saiman-Csrf: 1`). */
export function apiPost<T>(path: string, body: unknown): Promise<T> {
  if (isReplayMode) {
    return Promise.reject(
      new ApiError("replay", 0, "This is a recorded demo; actions are disabled.", null),
    );
  }
  return request<T>("POST", path, {
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-Saiman-Csrf": "1",
    },
    body: JSON.stringify(body),
  });
}

export interface RunEventSubscription {
  close(): void;
}

export interface SubscribeOptions {
  /** Called once when the stream is over: terminal event, 204, or a permanent error. */
  onClose?: () => void;
  /** Called on transient errors while the browser is reconnecting. */
  onReconnecting?: () => void;
}

/**
 * Follows a run over SSE with the native `EventSource`. The browser resends `Last-Event-ID` (the
 * seq) on its own reconnects, so the server resumes after the last event we saw. Each event is
 * validated by `parseRunEvent`; invalid ones are dropped. The caller merges by `seq`.
 *
 * The stream ends on a terminal event or when the server answers 204 (nothing left): in both cases
 * the `EventSource` is closed here so the browser does not reconnect forever.
 */
export function subscribeRunEvents(
  runId: string,
  onEvent: (event: RunEvent) => void,
  options: SubscribeOptions = {},
): RunEventSubscription {
  const source = new EventSource(`/api/v1/runs/${encodeURIComponent(runId)}/events`);
  let closed = false;

  const finish = () => {
    if (closed) {
      return;
    }
    closed = true;
    source.close();
    options.onClose?.();
  };

  const handle = (message: MessageEvent) => {
    if (typeof message.data !== "string") {
      return;
    }
    let json: unknown;
    try {
      json = JSON.parse(message.data);
    } catch {
      return;
    }
    const event = parseRunEvent(json);
    if (event === null) {
      return;
    }
    onEvent(event);
    if (isTerminalEvent(event)) {
      finish();
    }
  };

  for (const type of RUN_EVENT_TYPES) {
    source.addEventListener(type, handle as EventListener);
  }

  source.onerror = () => {
    // CLOSED: the browser gave up (HTTP 204, 4xx, wrong content type), so there is nothing more.
    if (source.readyState === EventSource.CLOSED) {
      finish();
    } else {
      options.onReconnecting?.();
    }
  };

  return {
    close() {
      if (!closed) {
        closed = true;
        source.close();
      }
    },
  };
}
