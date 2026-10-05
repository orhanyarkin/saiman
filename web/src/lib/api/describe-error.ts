import { ApiError } from "@/lib/api/source";

/** Plain-language text for an API failure. `detail` is fixed server text, safe to show as-is. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.kind) {
      case "rate-limited":
        return error.retryAfterSeconds === null
          ? "Too many requests. Please try again in a moment."
          : `Too many requests. Please retry in ${String(error.retryAfterSeconds)} s.`;
      case "not-ready":
        return "The orchestrator is starting. Please retry in a few seconds.";
      case "network":
        return "Could not reach the orchestrator. Is the stack running (make up)?";
      case "replay":
        return error.message;
      default:
        return error.detail ?? error.message;
    }
  }
  return error instanceof Error ? error.message : "Something went wrong.";
}
