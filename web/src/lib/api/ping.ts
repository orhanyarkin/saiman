import { apiGet } from "@/lib/api/source";
import type { PingResponse } from "@/lib/api/types";

export type { PingResponse };

/** `GET /api/v1/ping`: proof the browser reaches the orchestrator (see ADR-0006). */
export function fetchPing(): Promise<PingResponse> {
  return apiGet<PingResponse>("/api/v1/ping");
}
