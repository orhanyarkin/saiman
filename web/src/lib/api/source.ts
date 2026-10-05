/**
 * The single data source for screens: `apiGet` for every read, `apiPost` for the mutations and
 * `subscribeRunEvents` for the run stream. Screens never call `fetch` directly. In replay mode
 * (ADR-0004, ADR-0026) the same three functions answer from a recording instead; every live request
 * carries `Authorization: Bearer` (ADR-0023) and never anything token-related in a URL.
 *
 * Every request is same-origin (the dev/preview proxy or nginx routes `/api`), so there is no CORS
 * surface and no base URL to configure.
 */
import { ApiError, LLM_DAILY_CAP_CODE, type ApiErrorKind } from "@/lib/api/errors";
import { replayGet, replayRunEvents } from "@/lib/api/replay";
import { connectSse } from "@/lib/api/sse";
import { authHeaders, mustAuthenticate, notifyUnauthorized } from "@/lib/auth/token-store";
import { isReplayMode } from "@/lib/mode";
import {
  isTerminalEvent,
  parseRunEvent,
  RUN_EVENT_TYPES,
  type RunEvent,
} from "@/lib/api/run-events";

export { isReplayMode };
export { ApiError, type ApiErrorKind };

function kindFor(status: number, code: string | null): ApiErrorKind {
  switch (status) {
    case 400:
      return "invalid";
    case 401:
      return "unauthorized";
    case 403:
      return "forbidden";
    case 404:
      return "not-found";
    case 409:
      return "conflict";
    case 429:
      return "rate-limited";
    case 503:
      return code === LLM_DAILY_CAP_CODE ? "daily-cap" : "not-ready";
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
  let code: string | null = null;
  let replayAvailable = false;
  try {
    const body: unknown = await response.json();
    if (typeof body === "object" && body !== null) {
      if ("detail" in body) {
        const value = body.detail;
        detail = typeof value === "string" && value.length <= 300 ? value : null;
      }
      if ("code" in body && typeof body.code === "string" && /^[A-Z_]{1,64}$/.test(body.code)) {
        code = body.code;
      }
      replayAvailable = "replayAvailable" in body && body.replayAvailable === true;
    }
  } catch {
    // No or non-JSON body: fall back to the status text.
  }
  return new ApiError(
    kindFor(response.status, code),
    response.status,
    `${method} ${path} failed: ${String(response.status)} ${response.statusText}`.trim(),
    detail,
    parseRetryAfter(response.headers.get("Retry-After")),
    code,
    replayAvailable,
  );
}

async function request<T>(method: "GET" | "POST", path: string, init: RequestInit): Promise<T> {
  if (mustAuthenticate()) {
    // A 401 was seen and no token has been entered since: do not hammer the API (the approvals
    // poll runs every 5 s) while the Connect dialog is waiting.
    throw new ApiError("unauthorized", 401, `${method} ${path} failed: not connected`);
  }
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      method,
      headers: { ...(init.headers as Record<string, string> | undefined), ...authHeaders() },
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw cause;
    }
    throw new ApiError("network", 0, `${method} ${path} failed: network error`);
  }
  if (response.status === 401) {
    notifyUnauthorized();
  }
  if (!response.ok) {
    throw await toApiError(method, path, response);
  }
  return (await response.json()) as T;
}

export function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  if (isReplayMode) {
    return replayGet<T>(path);
  }
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
 * Follows a run over SSE (`connectSse`: fetch with `Authorization` and `Last-Event-ID`). Each event
 * is validated by `parseRunEvent`; invalid ones, and events whose `runId` is not the one subscribed
 * to, are dropped. The caller merges by `seq`.
 *
 * The stream ends on a terminal event, when the server answers 204 (nothing left) or on a permanent
 * error; a 401 stops it and opens the Connect dialog. In replay mode the recorded events are played
 * back with their original (clamped) gaps instead.
 */
export function subscribeRunEvents(
  runId: string,
  onEvent: (event: RunEvent) => void,
  options: SubscribeOptions = {},
): RunEventSubscription {
  let closed = false;
  let connection: { close(): void } | null = null;

  const finish = () => {
    if (closed) {
      return;
    }
    closed = true;
    connection?.close();
    options.onClose?.();
  };

  const deliver = (event: RunEvent) => {
    if (closed || event.runId !== runId) {
      return;
    }
    onEvent(event);
    if (isTerminalEvent(event)) {
      finish();
    }
  };

  if (isReplayMode) {
    connection = replayRunEvents(runId, {
      onEvent: deliver,
      onClose: finish,
    });
  } else {
    connection = connectSse({
      url: `/api/v1/runs/${encodeURIComponent(runId)}/events`,
      headers: authHeaders,
      onMessage: (message) => {
        if (!RUN_EVENT_TYPES.includes(message.event as RunEvent["type"])) {
          return;
        }
        let json: unknown;
        try {
          json = JSON.parse(message.data);
        } catch {
          return;
        }
        const event = parseRunEvent(json);
        if (event) {
          deliver(event);
        }
      },
      onEnd: finish,
      onUnauthorized: () => {
        notifyUnauthorized();
        finish();
      },
      onReconnecting: () => {
        if (!closed) {
          options.onReconnecting?.();
        }
      },
    });
  }

  return {
    close() {
      if (!closed) {
        closed = true;
        connection.close();
      }
    },
  };
}
