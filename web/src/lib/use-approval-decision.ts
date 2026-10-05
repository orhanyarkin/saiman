import { useMutation, useQueryClient } from "@tanstack/react-query";

import { decideApproval, queryKeys } from "@/lib/api/queries";
import { ApiError } from "@/lib/api/source";

/**
 * Approve or reject one approval. Shared by the inline run-view card and the approvals list, so a
 * decision behaves the same everywhere: whatever the outcome (including 409, "already decided or
 * expired"), the pending list, the run and its payments are refetched so the screen shows the truth.
 */
export function useApprovalDecision(runId: string, approvalId: string, onSettled?: () => void) {
  const queryClient = useQueryClient();
  const decision = useMutation({
    mutationFn: (choice: "APPROVE" | "REJECT") => decideApproval(runId, approvalId, choice),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.approvalsPending });
      void queryClient.invalidateQueries({ queryKey: queryKeys.run(runId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.spendAll });
      onSettled?.();
    },
  });
  const conflict = decision.error instanceof ApiError && decision.error.kind === "conflict";
  return { decision, conflict };
}
