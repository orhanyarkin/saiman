import { useQuery } from "@tanstack/react-query";

import { meQuery } from "@/lib/api/queries";
import type { Me } from "@/lib/api/types";
import { isReplayMode } from "@/lib/mode";

export interface Capabilities {
  /** Who the API says the token is, or null while unknown. */
  me: Me | null;
  /**
   * May change state: start runs, decide approvals, run reconciliation. Safe default: false
   * (READER) until `/me` says OPERATOR, so an unavailable `/me` hides the actions instead of
   * offering ones the API would refuse. Never true in a recording.
   */
  canOperate: boolean;
  /** `/me` has not answered yet (or failed): `canOperate` is the safe default until it does. */
  pending: boolean;
}

export function useCapabilities(): Capabilities {
  const me = useQuery(meQuery());
  const data = me.data ?? null;
  return {
    me: data,
    pending: !isReplayMode && me.isPending,
    canOperate: !isReplayMode && (data?.roles.includes("OPERATOR") ?? false),
  };
}
