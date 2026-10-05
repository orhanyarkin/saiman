import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";

import { useAuth } from "@/components/auth/auth-context";
import { Button } from "@/components/ui/button";
import { useDocumentTitle } from "@/lib/hooks";
import { isReplayMode } from "@/lib/mode";

export const Route = createFileRoute("/connect")({
  component: ConnectPage,
});

/** A linkable home for the Connect dialog: opening this route opens the dialog. */
function ConnectPage() {
  useDocumentTitle("Connect");
  const { state, openDialog, disconnect } = useAuth();

  useEffect(() => {
    if (!isReplayMode) {
      openDialog();
    }
  }, [openDialog]);

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Connect to the API</h1>
      {isReplayMode ? (
        <p className="text-muted-foreground">
          This is a recorded demo: it needs no token and nothing in it is live.
        </p>
      ) : (
        <>
          <p className="text-muted-foreground max-w-prose">
            The dashboard talks to the orchestrator and the ledger with a bearer token. The token is
            kept in this page&apos;s memory (and in this tab&apos;s session storage only if you opt
            in). It is never written to local storage or put in a URL, and a reload without the
            opt-in asks again.
          </p>
          <p role="status">{state.connected ? "Connected." : "Not connected."}</p>
          <div className="flex gap-3">
            {state.connected ? (
              <Button variant="outline" onClick={disconnect}>
                Disconnect
              </Button>
            ) : (
              <Button onClick={openDialog}>Enter a token</Button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
