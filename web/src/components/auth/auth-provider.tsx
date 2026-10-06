import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";

import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { ConnectDialog } from "@/components/auth/connect-dialog";
import {
  connect,
  disconnect,
  dismissConnectDialog,
  getAuthState,
  onTokenChange,
  openConnectDialog,
  subscribeAuth,
} from "@/lib/auth/token-store";
import { isReplayMode } from "@/lib/mode";

/**
 * Exposes the token store to React and renders the Connect dialog. A change of token drops every
 * cached query result, so data fetched as one identity is never shown to another. In replay mode
 * there is no token and no dialog.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const state = useSyncExternalStore(subscribeAuth, getAuthState, getAuthState);

  useEffect(
    () =>
      onTokenChange(() => {
        void queryClient.resetQueries();
      }),
    [queryClient],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      connect,
      disconnect,
      openDialog: openConnectDialog,
      closeDialog: dismissConnectDialog,
    }),
    [state],
  );

  return (
    <AuthContext.Provider value={value}>
      {children}
      {isReplayMode ? null : <ConnectDialog />}
    </AuthContext.Provider>
  );
}
