export type ApiErrorKind =
  | "unauthorized" // 401: no or rejected token, the Connect dialog opens
  | "conflict" // 409
  | "rate-limited" // 429, see retryAfterSeconds
  | "daily-cap" // 503 with code LLM_DAILY_CAP_REACHED (ADR-0026)
  | "not-ready" // 503 (orchestrator still starting)
  | "not-found" // 404, or a path that is not in the recording
  | "invalid" // 400
  | "forbidden" // 403: the token is read-only (or the guard rejected the request)
  | "replay" // mutation attempted in replay mode
  | "network"
  | "http";

export const LLM_DAILY_CAP_CODE = "LLM_DAILY_CAP_REACHED";

/** True for a rejected start request that says the daily cap is used up (ADR-0026). */
export function isDailyCapError(error: unknown): boolean {
  return error instanceof ApiError && error.kind === "daily-cap";
}

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  /** RFC 9457 `detail`: fixed, server-authored text, safe to show as-is. */
  readonly detail: string | null;
  readonly retryAfterSeconds: number | null;
  /** RFC 9457 extension `code` (a closed server enum such as LLM_DAILY_CAP_REACHED). */
  readonly code: string | null;
  /** True when the server says a recorded demo is available instead (ADR-0026). */
  readonly replayAvailable: boolean;

  constructor(
    kind: ApiErrorKind,
    status: number,
    message: string,
    detail: string | null = null,
    retryAfterSeconds: number | null = null,
    code: string | null = null,
    replayAvailable = false,
  ) {
    super(message);
    this.name = "ApiError";
    this.kind = kind;
    this.status = status;
    this.detail = detail;
    this.retryAfterSeconds = retryAfterSeconds;
    this.code = code;
    this.replayAvailable = replayAvailable;
  }
}
