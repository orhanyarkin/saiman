import { ApiError } from "@/lib/api/source";

/** Plain-language text for an API failure. `detail` is fixed server text, safe to show as-is. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.kind) {
      case "rate-limited":
        return error.retryAfterSeconds === null
          ? "Too many requests. Please try again in a moment."
          : `Too many requests. Please retry in ${String(error.retryAfterSeconds)} s.`;
      case "unauthorized":
        return "Authentication required. Connect with an API token to continue.";
      case "forbidden":
        return "This token is read-only. Connect with an operator token to do this.";
      case "daily-cap":
        return (
          error.detail ??
          "The daily model budget is used up, so no new runs can start until 00:00 UTC."
        );
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
